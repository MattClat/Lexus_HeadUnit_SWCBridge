package com.mattc.swcbridge;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring buffer of everything the bridge sees and does.
 * This replaces logcat: the head unit is rarely reachable from a laptop, so the
 * diagnostics have to be readable on the unit's own screen.
 */
public final class EventLog {

    public interface Listener { void onLogChanged(); }

    /** Mirrored to logcat so a desk session can `adb logcat -s SwcBridge:*` instead of
     *  reading the on-screen list. In the car the on-screen list is the only view. */
    public static final String TAG = "SwcBridge";

    private static final int MAX = 600;
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    /** The persistent file carries a date too: its whole point is spanning days. */
    private static final SimpleDateFormat TS_FILE =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    // ---- persistent history -------------------------------------------------
    //
    // The in-memory ring dies with the process, which is useless for diagnosing a
    // failure that only shows up after the car has sat for days. This mirrors every
    // line to a rolling file, and seeds the on-screen log from it at startup -- so
    // opening the app shows what did or did not happen while nobody was looking.

    private static final String HISTORY_FILE = "history.log";
    private static final long HISTORY_MAX_BYTES = 192 * 1024;
    private static final int HISTORY_SEED_LINES = 300;

    /** Archives made by CLEAR: history-YYYY-MM-DD_HHmmss.log. Only the newest are kept. */
    private static final String ARCHIVE_PREFIX = "history-";
    private static final int ARCHIVES_KEPT = 20;

    /** Serialises every touch of the history file (append, rotate, archive). */
    private static final Object FILE_LOCK = new Object();

    private static Context sAppContext;

    private EventLog() {}

    /**
     * Called from SwcApp.onCreate, i.e. every time this process comes up.
     * v0.9: no longer reads the history file here. In lean mode a process starts for
     * every wheel press, and reading up to 192 KB each time would only add delay; the
     * screen loads the history itself (reloadFromFile) when it opens.
     */
    public static void attach(Context c) {
        sAppContext = c.getApplicationContext();
    }

    /** Called when the config screen opens: shows the tail of the persistent history. */
    public static void reloadFromFile() {
        seedFromHistory();
        notifyListeners();
    }

    public static void add(String line) {
        Log.i(TAG, line);
        synchronized (LINES) {
            // SimpleDateFormat is not thread-safe; TS is only ever used under this lock.
            LINES.add(TS.format(new Date()) + "  " + line);
            while (LINES.size() > MAX) LINES.remove(0);
        }
        appendToHistory(line);
        notifyListeners();
    }

    public static File historyFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, HISTORY_FILE);
    }

    /**
     * Opened and closed per line rather than held open: this log exists precisely to
     * survive the process being killed without warning, so nothing may sit in a buffer.
     * Events here are rare (a handful per drive), so the cost does not matter.
     */
    private static void appendToHistory(String line) {
        Context c = sAppContext;
        if (c == null) return;
        synchronized (FILE_LOCK) {
            try {
                File f = historyFile(c);
                if (f == null) return;

                if (f.length() > HISTORY_MAX_BYTES) rotate(f);

                OutputStreamWriter w =
                        new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                try {
                    w.write(TS_FILE.format(new Date()));
                    w.write("  ");
                    w.write(line);
                    w.write('\n');
                } finally {
                    w.close();
                }
            } catch (Throwable t) {
                // Never call add() from here -- that would recurse.
                Log.e(TAG, "history append failed", t);
            }
        }
    }

    /** Keeps the newer half, so the file never grows without bound. */
    private static void rotate(File f) {
        try {
            List<String> all = readLines(f);
            int from = all.size() / 2;
            OutputStreamWriter w =
                    new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            try {
                w.write("--- trimmed ---\n");
                for (int i = from; i < all.size(); i++) {
                    w.write(all.get(i));
                    w.write('\n');
                }
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "history rotate failed", t);
        }
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) out.add(line);
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Puts the tail of the previous days back on screen before this run's own lines. */
    private static void seedFromHistory() {
        Context c = sAppContext;
        if (c == null) return;
        try {
            File f = historyFile(c);
            if (f == null || !f.exists()) return;
            List<String> all;
            synchronized (FILE_LOCK) { all = readLines(f); }
            int from = Math.max(0, all.size() - HISTORY_SEED_LINES);
            synchronized (LINES) {
                // The file already holds this process's own lines, so this replaces the
                // in-memory list rather than adding to it.
                LINES.clear();
                for (int i = from; i < all.size(); i++) LINES.add(all.get(i));
            }
        } catch (Throwable t) {
            Log.e(TAG, "history seed failed", t);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String s : LINES) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /**
     * CLEAR button (v0.8). Before v0.8 this only emptied the screen, and the next start
     * re-read everything from history.log.
     *
     * Now: the current history.log is moved aside to history-YYYY-MM-DD_HHmmss.log (so
     * nothing is lost), history.log is deleted, and the screen is emptied. The new
     * history.log then starts with a single "log cleared" line naming the archive.
     *
     * @return the archive file, or null if there was nothing to archive
     */
    public static File clearAndArchive(Context ctx) {
        Context c = ctx.getApplicationContext();
        File archived = null;
        String problem = null;
        synchronized (FILE_LOCK) {
            try {
                File f = historyFile(c);
                if (f != null && f.exists()) {
                    if (f.length() > 0) {
                        File dest = uniqueArchiveName(f.getParentFile());
                        if (f.renameTo(dest) || copyThenDelete(f, dest)) {
                            archived = dest;
                        } else {
                            problem = "could not archive " + f.getName();
                        }
                    }
                    if (f.exists() && !f.delete()) {
                        problem = "could not delete " + f.getName();
                    }
                }
                if (f != null) pruneArchives(f.getParentFile());
            } catch (Throwable t) {
                problem = String.valueOf(t);
            }
        }
        synchronized (LINES) { LINES.clear(); }
        if (problem != null) add("! clear: " + problem);
        add("log cleared" + (archived != null
                ? " -- previous log saved as " + archived.getName()
                : " (history was already empty)"));
        return archived;
    }

    private static File uniqueArchiveName(File dir) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, ARCHIVE_PREFIX + stamp + ".log");
        int n = 2;
        while (f.exists()) {
            f = new File(dir, ARCHIVE_PREFIX + stamp + "-" + n++ + ".log");
        }
        return f;
    }

    private static boolean copyThenDelete(File from, File to) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(from);
            try {
                FileOutputStream out = new FileOutputStream(to, false);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return from.delete() || !from.exists();
        } catch (Throwable t) {
            Log.e(TAG, "archive copy failed", t);
            return false;
        }
    }

    /** Keeps the newest ARCHIVES_KEPT archives. Names sort by date, so a name sort works. */
    private static void pruneArchives(File dir) {
        if (dir == null) return;
        File[] all = dir.listFiles();
        if (all == null) return;
        List<File> archives = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith(ARCHIVE_PREFIX) && n.endsWith(".log")) archives.add(f);
        }
        if (archives.size() <= ARCHIVES_KEPT) return;
        java.util.Collections.sort(archives, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (int i = 0; i < archives.size() - ARCHIVES_KEPT; i++) {
            //noinspection ResultOfMethodCallIgnored
            archives.get(i).delete();
        }
    }

    public static void addListener(Listener l) {
        synchronized (LISTENERS) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    }

    public static void removeListener(Listener l) {
        synchronized (LISTENERS) { LISTENERS.remove(l); }
    }

    private static void notifyListeners() {
        List<Listener> copy;
        synchronized (LISTENERS) { copy = new ArrayList<>(LISTENERS); }
        for (Listener l : copy) {
            try { l.onLogChanged(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Writes the log to the app's external files dir. No storage permission needed
     * on API 19+, and the file is reachable from the unit's file manager or over USB.
     */
    public static File save(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "swcbridge-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".txt";
            File f = new File(dir, name);
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(Diagnostics.header(c));
                w.write("\n\n");
                w.write(dump());
            } finally {
                w.close();
            }
            return f;
        } catch (Throwable t) {
            add("! log save failed: " + t);
            return null;
        }
    }

    @SuppressWarnings("unused")
    public static String externalStateForDebug() {
        return Environment.getExternalStorageState();
    }
}
