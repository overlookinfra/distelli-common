/*
  $Id: $
  @file DiskObjectStore.java
  @brief Contains the DiskObjectStore.java class

  @author Rahul Singh [rsingh]
*/
package com.distelli.objectStore.impl.disk;

import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.persistence.EntityNotFoundException;

import com.distelli.objectStore.*;
import com.distelli.objectStore.impl.AbstractObjectStore;
import com.distelli.objectStore.impl.ObjectStoreBuilder;
import com.distelli.persistence.PageIterator;
import com.google.inject.assistedinject.Assisted;

public class DiskObjectStore extends AbstractObjectStore
{
    private File _root = null;
    private File _bucketsRoot = null;

    private static final String KEY_POSTFIX = ".obj";

    public interface Factory {
        public DiskObjectStore create(ObjectStoreBuilder builder);
    }

    public DiskObjectStore(File rootDir) {
        if(rootDir == null)
            throw(new IllegalArgumentException("Invalid Disk Storage Root: "+rootDir));
        //if it exists ensure that its a directory
        if(rootDir.exists())
        {
            //check to make sure that the specified root is a dir
            if(!rootDir.isDirectory())
                throw(new IllegalArgumentException("Invalid Disk Storage Root: "+rootDir+" is not a directory"));
        }
        else
        {
            //create the dir
            rootDir.mkdirs();
        }

        //create the buckets subdir
        File bucketsRoot = new File(rootDir.getAbsolutePath(), "buckets");
        if(!bucketsRoot.exists())
            bucketsRoot.mkdirs();
        _bucketsRoot = bucketsRoot;
        _root = rootDir;
    }

    @Inject
    public DiskObjectStore(@Assisted ObjectStoreBuilder builder)
    {
        this(builder.getDiskStorageRoot());
        ObjectStoreType type = builder.getObjectStoreProvider();
        if(type == null || type != ObjectStoreType.DISK)
            throw(new IllegalArgumentException("Invalid ObjectStoreType: "+type));
    }

    @Override
    public void createBucket(String bucketName) {
        File bucketDir = new File(_bucketsRoot.getAbsolutePath(), bucketName);
        if(bucketDir.exists())
            return;
        bucketDir.mkdirs();
    }

    @Override
    public void deleteBucket(String bucketName) throws AccessControlException {
        File bucketDir = new File(_bucketsRoot.getAbsolutePath(), bucketName);
        if(!bucketDir.exists())
            return;
        try {
            try(DirectoryStream<Path> dirStream = Files.newDirectoryStream(bucketDir.toPath())) {
                if(dirStream.iterator().hasNext())
                    throw(new IllegalStateException("Bucket "+bucketName+" is not empty"));
            }

            Files.delete(bucketDir.toPath());
        } catch(Throwable t) {
            throw(new AccessControlException(t.getMessage()));
        }
    }

    @Override
    public void put(ObjectKey objectKey, long contentLength, InputStream in) {
        File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
        if(!bucketRoot.exists())
            throw(new EntityNotFoundException("Bucket "+objectKey.getBucket()+" does not exist"));
        File objFile = new File(bucketRoot, toKeyId(objectKey.getKey()));
        File parentDir = objFile.getParentFile();
        if(parentDir != null && !parentDir.exists())
            parentDir.mkdirs();

        try {
            FileOutputStream out = new FileOutputStream(objFile);
            byte[] buf = new byte[1024*1024];
            int bytesRead = 0;
            while((bytesRead = in.read(buf)) != -1)
                out.write(buf, 0, bytesRead);
            out.close();
            in.close();
        } catch(Throwable t) {
            throw(new RuntimeException(t));
        }
    }

    // Returns null if entity does not exist.
    @Override
    public ObjectMetadata head(ObjectKey objectKey) {
        File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
        if(!bucketRoot.exists())
            return null;
        File objFile = new File(bucketRoot, toKeyId(objectKey.getKey()));
        if(!objFile.exists())
            return null;
        long len = objFile.length();
        return ObjectMetadata
        .builder()
        .bucket(objectKey.getBucket())
        .key(objectKey.getKey())
        .contentLength(len)
        .build();
    }

    @Override
    public <T> T get(ObjectKey objectKey, ObjectReader<T> objectReader, Long start, Long end)
        throws EntityNotFoundException, IOException
    {
        File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
        if(!bucketRoot.exists())
            throw(new EntityNotFoundException("NotFound: "+objectKey+" bucketsRoot="+_bucketsRoot));
        File objFile = new File(bucketRoot, toKeyId(objectKey.getKey()));
        if(!objFile.exists())
            throw(new EntityNotFoundException("NotFound: "+objectKey+" bucketsRoot="+_bucketsRoot));
        FileInputStream fileIn = new FileInputStream(objFile);
        if(start != null)
            fileIn.skip(start.longValue());
        InputStream in = null;
        if(end != null)
        {
            if(start != null && end < start)
                throw(new IllegalArgumentException("end ["+end+"] cannot be less than start ["+start+"]"));
            in = new LimitingInputStream(fileIn, end-start);
        }
        if(in == null)
            in = fileIn;
        ObjectMetadata objectMetadata = ObjectMetadata
        .builder()
        .bucket(objectKey.getBucket())
        .key(objectKey.getKey())
        .contentLength(objFile.length())
        .build();

        return objectReader.read(objectMetadata, in);
    }

    @Override
    public List<ObjectKey> list(ObjectKey objectKey, PageIterator iterator)
    {
        final List<ObjectKey> keys = new ArrayList<ObjectKey>();
        File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
        if(!bucketRoot.exists()) {
            throw new EntityNotFoundException("NotFound: "+objectKey+" bucketsRoot="+_bucketsRoot);
        }

        final File objFile = new File(bucketRoot, objectKey.getKey());
        File parentDir = objFile.getParentFile();
        if(parentDir == null || !parentDir.exists()) {
            iterator.setMarker(null);
            return keys;
        }

        File afterFile = null;
        if ( null != iterator.getMarker() ) {
            afterFile = new File(iterator.getMarker());
        }

        AtomicInteger remaining = new AtomicInteger(iterator.getPageSize());
        if ( walk(parentDir, objFile.getName(), 0, afterFile, (file) -> {
                    ObjectKey elm = toObjectkey(file);
                    if ( remaining.getAndDecrement() <= 0 ) {
                        iterator.setMarker(elm.getKey());
                        return false;
                    }
                    keys.add(elm);
                    return true;
                }) )
        {
            iterator.setMarker(null);
        }
        return keys;
    }

    @Override
    public void delete(ObjectKey objectKey)
        throws EntityNotFoundException
    {
        try {
            File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
            if(!bucketRoot.exists())
                throw(new EntityNotFoundException("NotFound: "+objectKey+" bucketsRoot="+_bucketsRoot));
            File objFile = new File(bucketRoot, toKeyId(objectKey.getKey()));
            // May have already been deleted concurrently, so we ignore this which
            // is consistent with S3 behavior.
            if(!objFile.exists()) return;
            Files.delete(objFile.toPath());
        } catch(IOException ioe) {
            throw(new RuntimeException(ioe));
        }
    }

    @Override
    public URI createSignedGet(ObjectKey objectKey, long timeout, TimeUnit unit)
        throws EntityNotFoundException
    {
        // S3 never checks if these paths exist, it simply returns a string with
        // credentials:
        File bucketRoot = new File(_bucketsRoot, objectKey.getBucket());
        File objFile = new File(bucketRoot, toKeyId(objectKey.getKey()));
        return URI.create("file://"+objFile.getAbsoluteFile().getAbsolutePath());
    }

    @Override
    public ObjectPartKey newMultipartPut(ObjectKey objectKey) {
        return null;
    }

    @Override
    public ObjectPartId multipartPut(ObjectPartKey partKey, int partNum, long contentLength, InputStream in) {
        return null;
    }

    @Override
    public void abortPut(ObjectPartKey partKey) {
    }

    @Override
    public void completePut(ObjectPartKey partKey, List<ObjectPartId> partKeys) {
    }

    /**
     * NOTE: This method does NOT check for symlink loops, therefore it could go into infinite recursion!
     *
     * @param root is the place to begin the walk.
     *
     * @param startsWith if non-null, the first files must begin with this string.
     *
     * @param depth is how many directories deep we have walked. Initially this should be zero.
     *
     * @param afterFile is the path relative to the root of where to begin the
     *     file walking.
     *
     * @param visitor is called when visiting each file. The walk is stopped if false is returned.
     *
     * @return false to indicate the walk was prematurely terminated by visitor returning false.
     */
    private static boolean walk(File root, String startsWith, int depth, File afterFile, Function<File, Boolean> visitor) {
        File[] list;
        String afterFileName = null;
        if ( null != afterFile ) {
            int afterFileDepth = 0;
            for ( File cur=afterFile; cur != null; cur = cur.getParentFile() ) {
                afterFileDepth++;
            }
            afterFileDepth -= depth;
            if ( afterFileDepth > 0 ) {
                File afterFileForDepth = afterFile;
                while ( --afterFileDepth > 0 ) {
                    afterFileForDepth = afterFileForDepth.getParentFile();
                }
                afterFileName = afterFileForDepth.getName();
            }
        }
        if ( null != afterFileName || null != startsWith ) {
            final String finalFileName = afterFileName;
            list = root.listFiles((dir, name) -> {
                    return
                    ( null == finalFileName || name.compareTo(finalFileName) >= 0 ) &&
                    ( null == startsWith || name.startsWith(startsWith) );
                });
        } else {
            list = root.listFiles();
        }
        if ( null == list ) return true;
        Arrays.sort(list, (file1, file2) -> file1.getName().compareTo(file2.getName()));

        for ( File file : list ) {
            if ( file.isDirectory() ) {
                if ( ! walk(new File(root, file.getName()), null, depth+1, afterFile, visitor) ) {
                    return false;
                }
            } else if ( Boolean.FALSE == visitor.apply(file) ) {
                return false;
            }
        }
        return true;
    }
    
    private ObjectKey toObjectkey(File file)
    {
        if(file.isDirectory())
            throw(new IllegalArgumentException("Invalid file: "+file));
        String rootPath = _bucketsRoot.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        Path objectKeyPath = Paths.get(filePath.substring(rootPath.length()));
        String bucket = objectKeyPath.iterator().next().toString();
        //add +2 to the substring to account for the 2 slashes before and after the bucket name
        String key = objectKeyPath.toString().substring(bucket.length()+2);
        if(bucket.startsWith("/"))
            bucket = bucket.substring(1);
        if(key.startsWith("/"))
            key = key.substring(1);
        if(key.endsWith(KEY_POSTFIX))
            key = key.substring(0, key.length() - KEY_POSTFIX.length());
        return ObjectKey
        .builder()
        .bucket(bucket)
        .key(key)
        .build();
    }

    /**
       A Key Id is the canonical Id of the key as stored on disk. It
       is the name of the actual file on disk and is generated by
       appending the KEY_POSTFIX to the key name.

       If the key ends with a trailing slash then the trailing slash
       is removed before the KEY_POSTFIX is appended

       if key is null then a NullPointerException is thrown
    */
    private String toKeyId(String key)
    {
        key = key.trim();
        if(key.endsWith("/"))
            key = key.substring(0, key.length() -1);
        if(key.isEmpty())
            throw(new IllegalArgumentException("Invalid key: "+key));
        return String.format("%s%s", key, KEY_POSTFIX);
    }
}
