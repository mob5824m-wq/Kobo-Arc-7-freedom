package ca.arc7freedom.support;

import java.io.File;
import java.io.IOException;

/** Bounded read-only storage accounting. Never follows symlinks, removes files or opens contents. */
public final class StorageUsage {
    private StorageUsage() {}
    public static final class Result {
        public long bytes, files;
        public boolean partial;
        private int entries;
        private long deadline = System.currentTimeMillis()+1500L;
    }
    public static Result measure(File directory) {
        Result result=new Result();
        try { if(directory!=null)walk(directory.getAbsoluteFile(),result,0); }
        catch(Exception unavailable){result.partial=true;}
        return result;
    }
    private static void walk(File file,Result result,int depth)throws IOException {
        if(++result.entries>5000||depth>12||System.currentTimeMillis()>result.deadline){result.partial=true;return;}
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())){result.partial=true;return;}
        if(!file.exists())return;
        if(file.isFile()){result.files++;result.bytes+=file.length();return;}
        if(file.isDirectory()){
            File[] children=file.listFiles();if(children==null){result.partial=true;return;}
            for(File child:children){if(result.entries>5000||System.currentTimeMillis()>result.deadline){result.partial=true;break;}walk(child,result,depth+1);}
        }
    }
}
