package com.example.terrariatoolbox.data.shizuku;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import androidx.annotation.Keep;
import java.io.File;
import static com.example.terrariatoolbox.domain.FileOperationsKt.TARGET_PATH;

/** Created by Shizuku, not an exported Android Service. */
@Keep
public final class ConfigUserService extends IConfigFileService.Stub {
    private final int ownerUid;
    private final FixedConfigFile file = new FixedConfigFile(new File(TARGET_PATH));
    private final SaveDirectoryFiles saves = new SaveDirectoryFiles();
    @Keep public ConfigUserService(Context context) { ownerUid = context.getApplicationInfo().uid; }
    private void checkCaller() {
        if (Os.getuid() != 2000 || ownerUid / 100000 != 0 || Binder.getCallingUid() != ownerUid)
            throw new SecurityException("Only the owning main-user application in shell mode is supported");
    }
    @Override public Bundle probe() { checkCaller(); return file.probe(); }
    @Override public ParcelFileDescriptor openRead() { checkCaller(); return file.openRead(); }
    @Override public ParcelFileDescriptor openOverwrite(String expectedBeforeHash) {
        checkCaller(); return file.openOverwrite(expectedBeforeHash);
    }
    @Override public String[] saveAccounts() { checkCaller(); return saves.accounts(); }
    @Override public Bundle listSaves(int edition, String account) { checkCaller(); return saves.list(edition, account); }
    @Override public ParcelFileDescriptor openSaveRead(int edition, String account, String name) { checkCaller(); return saves.read(edition, account, name); }
    @Override public Bundle importSave(int edition, String account, String name, ParcelFileDescriptor source, long size, String hash) {
        checkCaller(); return saves.importSave(edition, account, name, source, size, hash);
    }
    // Shizuku itself invokes this reserved transaction during unbinding or upgrade.
    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller == ownerUid || caller == 2000) System.exit(0);
        else throw new SecurityException("Invalid destroy caller");
    }
}
