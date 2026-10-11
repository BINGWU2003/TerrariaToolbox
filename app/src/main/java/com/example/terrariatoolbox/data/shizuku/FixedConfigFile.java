package com.example.terrariatoolbox.data.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.security.MessageDigest;

/** No create/rename/delete operations. The Binder service always uses its fixed production path. */
public final class FixedConfigFile {
    public static final int MISSING = 1, NOT_REGULAR = 2, TOO_LARGE = 3, ACCESS = 4,
            HASH_CHANGED = 5, IO = 6;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final File target;
    // Package-private constructor allows isolated androidTest fixtures, not Binder-supplied paths.
    FixedConfigFile(File target) { this.target = target; }

    private FileDescriptor open(int mode) {
        FileDescriptor fd = null;
        try {
            StructStat pathStat = Os.lstat(target.getAbsolutePath());
            if (!OsConstants.S_ISREG(pathStat.st_mode)) throw new ConfigFileException(NOT_REGULAR);
            fd = Os.open(target.getAbsolutePath(), mode | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new ConfigFileException(NOT_REGULAR);
            if (stat.st_size > MAX_BYTES) throw new ConfigFileException(TOO_LARGE);
            return fd;
        } catch (ErrnoException error) {
            close(fd);
            int code = error.errno == OsConstants.ENOENT ? MISSING :
                    error.errno == OsConstants.ELOOP ? NOT_REGULAR : ACCESS;
            throw new ConfigFileException(code);
        } catch (RuntimeException error) { close(fd); throw error; }
    }
    private static void close(FileDescriptor fd) {
        if (fd != null) try { Os.close(fd); } catch (Exception ignored) { }
    }
    public Bundle probe() {
        FileDescriptor read = open(OsConstants.O_RDONLY);
        Bundle result = new Bundle();
        try {
            result.putString("path", target.getAbsolutePath());
            result.putInt("uid", Os.getuid());
            result.putLong("size", Os.fstat(read).st_size);
        } catch (ErrnoException error) { throw new ConfigFileException(IO); }
        finally { close(read); }
        boolean writable = false;
        try { FileDescriptor write = open(OsConstants.O_RDWR); close(write); writable = true; }
        catch (ConfigFileException ignored) { }
        result.putBoolean("writable", writable);
        return result;
    }
    public ParcelFileDescriptor openRead() { return parcel(open(OsConstants.O_RDONLY)); }
    private static ParcelFileDescriptor parcel(FileDescriptor fd) {
        try { return ParcelFileDescriptor.dup(fd); }
        catch (Exception error) { throw new ConfigFileException(IO); }
        finally { close(fd); }
    }
    public ParcelFileDescriptor openOverwrite(String expectedHash) {
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("expected hash required");
        FileDescriptor fd = open(OsConstants.O_RDWR);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long offset = 0;
            while (true) {
                int count = Os.pread(fd, buffer, 0, buffer.length, offset);
                if (count == 0) break;
                offset += count;
                if (offset > MAX_BYTES) throw new ConfigFileException(TOO_LARGE);
                digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!hash.toString().equals(expectedHash)) throw new ConfigFileException(HASH_CHANGED);
            // Prepare the transferred descriptor before any destructive operation.
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(fd);
            try {
                Os.lseek(fd, 0, OsConstants.SEEK_SET);
                Os.ftruncate(fd, 0);
                return result;
            } catch (Exception error) { result.close(); throw error; }
        } catch (ConfigFileException error) { throw error; }
        catch (Exception error) { throw new ConfigFileException(IO); }
        finally { close(fd); }
    }
}
