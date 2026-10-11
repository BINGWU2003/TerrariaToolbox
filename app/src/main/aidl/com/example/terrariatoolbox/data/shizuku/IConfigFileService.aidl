package com.example.terrariatoolbox.data.shizuku;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IConfigFileService {
    void destroy() = 16777114;
    Bundle probe() = 1;
    ParcelFileDescriptor openRead() = 2;
    ParcelFileDescriptor openOverwrite(String expectedBeforeHash) = 3;
    String[] saveAccounts() = 4;
    Bundle listSaves(int edition, String account) = 5;
    ParcelFileDescriptor openSaveRead(int edition, String account, String name) = 6;
    Bundle importSave(int edition, String account, String name, in ParcelFileDescriptor source, long size, String hash) = 7;
}
