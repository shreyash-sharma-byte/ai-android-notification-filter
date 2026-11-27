package io.github.shreyashsharmabyte.notifycleaner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/** Validation and atomic replacement for downloadable classifier weights. No Android imports. */
public final class ModelStore {
    public static final int MIN_FEATURES = 500;

    private ModelStore() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Counts usable feature rows in a gzipped model. Returns -1 when it cannot be read. */
    public static int featureCount(byte[] gz) {
        if (gz == null || gz.length == 0) return -1;
        int rows = 0;
        boolean bias = false;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#bias")) { bias = true; continue; }
                if (line.length() < 5) continue;
                String[] parts = line.split("\t");
                if (parts.length == 3) rows++;
            }
        } catch (IOException e) {
            return -1;
        }
        return bias ? rows : -1;
    }

    public static boolean acceptable(byte[] gz) {
        return featureCount(gz) >= MIN_FEATURES;
    }

    public static byte[] read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes tmp, then renames over target, keeping the old one as prev. Returns success. */
    public static boolean swap(File tmp, File target, File prev) {
        if (tmp == null || !tmp.isFile()) return false;
        if (target.exists() && prev != null) {
            if (prev.exists()) prev.delete();
            if (!target.renameTo(prev)) return false;
        }
        return tmp.renameTo(target);
    }

    public static String meta(long version, String sha, int minApp, String notes) {
        return "{\"version\":" + version + ",\"sha256\":\"" + sha + "\",\"minApp\":" + minApp
                + ",\"notes\":\"" + (notes == null ? "" : notes.replace("\"", "'")) + "\"}";
    }

    public static long version(String meta) {
        if (meta == null) return 0;
        int i = meta.indexOf("\"version\":");
        if (i < 0) return 0;
        int j = i + 10;
        StringBuilder sb = new StringBuilder();
        while (j < meta.length()) {
            char c = meta.charAt(j);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0) break;
            j++;
        }
        try {
            return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String field(String json, String key) {
        if (json == null) return "";
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int j = i + key.length() + 2;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != ':') return "";
        j++;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != '"') return "";
        j++;
        int end = json.indexOf('"', j);
        return end < 0 ? "" : json.substring(j, end);
    }
}
