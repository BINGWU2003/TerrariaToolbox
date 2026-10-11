package com.example.terrariatoolbox.data.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.example.terrariatoolbox.domain.SaveNames;
import java.io.File;
import java.io.FileDescriptor;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Binder arguments select an edition/account, never an arbitrary filesystem path. */
public final class SaveDirectoryFiles {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final File international, chinese;
    public SaveDirectoryFiles() {
        this(new File("/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid"),
                new File("/storage/emulated/0/Android/data/com.xd.terraria"));
    }
    SaveDirectoryFiles(File international, File chinese) { this.international = international; this.chinese = chinese; }
    private static IllegalArgumentException failure(String code) { return new IllegalArgumentException("SAVE_ERROR:" + code); }
    private static boolean exists(File file) {
        try { Os.lstat(file.getAbsolutePath()); return true; }
        catch (ErrnoException error) { if (error.errno == OsConstants.ENOENT) return false; throw failure("ACCESS"); }
    }
    private static void directory(File file) {
        try { if (!OsConstants.S_ISDIR(Os.lstat(file.getAbsolutePath()).st_mode)) throw failure("INVALID"); }
        catch (ErrnoException error) { throw failure(error.errno == OsConstants.ENOENT ? "MISSING_ROOT" : "ACCESS"); }
    }
    public synchronized String[] accounts() {
        directory(chinese);
        File[] files = chinese.listFiles();
        if (files == null) throw failure("ACCESS");
        ArrayList<String> result = new ArrayList<>();
        for (File file : files) if (file.getName().matches("[0-9]{1,20}")) {
            try { directory(file); result.add(file.getName()); } catch (IllegalArgumentException ignored) { }
        }
        if (result.size() > 500) throw failure("LIMIT");
        java.util.Collections.sort(result);
        return result.toArray(new String[0]);
    }
    private File account(int edition, String account) {
        if (edition == 0 && "".equals(account)) { directory(international); return international; }
        if (edition != 1 || account == null || !account.matches("[0-9]{1,20}")) throw failure("INVALID");
        directory(chinese);
        File result = new File(chinese, account);
        directory(result);
        return result;
    }
    private File saveDirectory(int edition, String account, String type, boolean create) {
        File parent = account(edition, account);
        if (!type.equals("Players") && !type.equals("Worlds")) throw failure("INVALID");
        File dir = new File(parent, type);
        if (!exists(dir)) {
            if (!create) return dir;
            if (!dir.mkdir() && !exists(dir)) throw failure("ACCESS");
        }
        directory(dir);
        return dir;
    }
    public synchronized Bundle list(int edition, String account) {
        ArrayList<Bundle> entries = new ArrayList<>();
        for (String type : new String[]{"Players", "Worlds"}) {
            File dir = saveDirectory(edition, account, type, false);
            if (!exists(dir)) continue;
            File[] files = dir.listFiles();
            if (files == null) throw failure("ACCESS");
            Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File file : files) {
                try {
                    if (!SaveNames.directory(file.getName()).equals(type)) continue;
                    StructStat stat = Os.lstat(file.getAbsolutePath());
                    if (!OsConstants.S_ISREG(stat.st_mode)) continue;
                    Bundle entry = new Bundle();
                    entry.putString("name", file.getName()); entry.putLong("size", stat.st_size);
                    entry.putLong("modified", stat.st_mtime * 1000);
                    entries.add(entry);
                    if (entries.size() > 1000) throw failure("LIMIT");
                } catch (ErrnoException ignored) { }
                catch (IllegalArgumentException error) {
                    if ("SAVE_ERROR:LIMIT".equals(error.getMessage())) throw error;
                }
            }
        }
        Bundle result = new Bundle(); result.putParcelableArrayList("entries", entries); return result;
    }
    private static FileDescriptor openReadFile(File file) {
        FileDescriptor fd = null;
        try {
            if (!OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode)) throw failure("INVALID");
            fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) throw failure("INVALID");
            if (stat.st_size <= 0 || stat.st_size > MAX_BYTES) throw failure("LIMIT");
            return fd;
        } catch (Exception error) { close(fd); if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error; throw failure("ACCESS"); }
    }
    private static void close(FileDescriptor fd) { if (fd != null) try { Os.close(fd); } catch (Exception ignored) { } }
    public synchronized ParcelFileDescriptor read(int edition, String account, String name) {
        String type;
        try { type = SaveNames.directory(name); } catch (Exception error) { throw failure("INVALID"); }
        File dir = saveDirectory(edition, account, type, false);
        directory(dir);
        FileDescriptor fd = openReadFile(new File(dir, name));
        try { return ParcelFileDescriptor.dup(fd); } catch (Exception error) { throw failure("ACCESS"); }
        finally { close(fd); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    public synchronized Bundle importSave(int edition, String account, String name, ParcelFileDescriptor source, long size, String hash) {
        FileDescriptor output = null;
        File created = null;
        long createdInode = -1;
        try {
            if (source == null || size <= 0 || size > MAX_BYTES || hash == null || !hash.matches("[0-9a-f]{64}")) throw failure("INVALID");
            String type;
            try { type = SaveNames.directory(name); } catch (Exception error) { throw failure("INVALID"); }
            File dir = saveDirectory(edition, account, type, true);
            for (int attempt = 0; attempt < 1000; attempt++) {
                String[] existing = dir.list();
                if (existing == null) throw failure("ACCESS");
                created = new File(dir, SaveNames.available(name, Arrays.asList(existing)));
                try {
                    output = Os.open(created.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
                    createdInode = Os.fstat(output).st_ino;
                    break;
                } catch (ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
            }
            if (output == null) throw failure("ACCESS");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; long offset = 0;
            while (true) {
                int n = Os.pread(source.getFileDescriptor(), buffer, 0, buffer.length, offset);
                if (n == 0) break;
                offset += n; if (offset > size) throw failure("VERIFY");
                digest.update(buffer, 0, n);
                int written = 0;
                while (written < n) { int amount = Os.write(output, buffer, written, n - written); if (amount <= 0) throw failure("WRITE"); written += amount; }
            }
            if (offset != size || !hex(digest.digest()).equals(hash)) throw failure("VERIFY");
            Os.fsync(output); Os.close(output); output = null;
            FileDescriptor verify = openReadFile(created);
            try {
                if (Os.fstat(verify).st_ino != createdInode || Os.fstat(verify).st_size != size) throw failure("VERIFY");
                digest.reset(); offset = 0;
                while (true) { int n = Os.pread(verify, buffer, 0, buffer.length, offset); if (n == 0) break; offset += n; if (offset > size) throw failure("VERIFY"); digest.update(buffer, 0, n); }
                if (offset != size || !hex(digest.digest()).equals(hash)) throw failure("VERIFY");
            } finally { close(verify); }
            Bundle result = new Bundle(); result.putString("name", created.getName()); return result;
        } catch (Exception error) {
            close(output); output = null;
            if (created != null && createdInode != -1) {
                try { if (Os.lstat(created.getAbsolutePath()).st_ino == createdInode) Os.remove(created.getAbsolutePath()); }
                catch (Exception ignored) { throw failure("WRITE"); }
            }
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw failure("WRITE");
        } finally { close(output); if (source != null) try { source.close(); } catch (Exception ignored) { } }
    }
}
