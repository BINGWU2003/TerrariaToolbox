package io.github.bingwu2003.terrariatoolbox.data.shizuku;

/** A public SDK exception category which Parcel can marshal without hidden framework APIs. */
public final class ConfigFileException extends IllegalArgumentException {
    public final int errorCode;
    public ConfigFileException(int code) { super("FILE_ERROR:" + code); errorCode = code; }
}
