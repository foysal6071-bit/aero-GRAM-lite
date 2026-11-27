package org.telegram.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.telegram.messenger.AndroidUtilities.dp;

/** AeroGram: everything downloaded by the app, with Save to phone / Open / Share / Delete in the 3-dot menu. */
public class AeroDownloadsActivity extends BaseFragment {

    private static final int ALL = 0, IMAGE = 1, VIDEO = 2, AUDIO = 3, FILE = 4;
    private static final String[] FILTER_NAMES = {"All", "Photos", "Videos", "Audio", "Files"};

    private static class Item {
        File file;
        String name;
        String mime;
        String ext;
        int kind;
        long size;
        long date;
    }

    private Context ctx;
    private boolean destroyed;
    private boolean loading = true;
    private final ArrayList<Item> all = new ArrayList<>();
    private final ArrayList<Item> shown = new ArrayList<>();
    private int filter = ALL;

    private TextView summary;
    private LinearLayout chips;
    private RecyclerView list;
    private FrameLayout stateHolder;
    private Adapter adapter;

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(60);

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setBackButtonImage(R.drawable.aero_ic_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Downloads");
        actionBar.setBackgroundColor(AeroPrefs.accent());
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(AeroPrivacyKit.background());
        fragmentView = page;

        summary = AeroUi.text(context, "", 13.5f, AeroPrivacyKit.muted(), false);
        summary.setPadding(dp(22), dp(14), dp(22), dp(6));
        page.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(16), dp(4), dp(16), dp(8));
        hs.addView(chips, new ViewGroup.LayoutParams(-2, -2));
        page.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(context);
        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setPadding(dp(16), dp(4), dp(16), dp(24));
        adapter = new Adapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        stateHolder = new FrameLayout(context);
        body.addView(stateHolder, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        renderHeader();
        renderState();
        load();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ctx != null && !loading) load();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        thumbPool.shutdownNow();
        super.onFragmentDestroy();
    }

    // ---------------------------------------------------------------- data

    private void load() {
        // names of files Telegram knows about (original file names), gathered on the UI thread
        final HashMap<String, String> names = new HashMap<>();
        try {
            DownloadController dc = DownloadController.getInstance(currentAccount);
            ArrayList<MessageObject> known = new ArrayList<>(dc.recentDownloadingFiles);
            for (MessageObject m : known) {
                try {
                    File f = FileLoader.getInstance(currentAccount).getPathToMessage(m.messageOwner);
                    String n = m.getDocumentName();
                    if (f != null && !TextUtils.isEmpty(n)) names.put(f.getAbsolutePath(), n);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }

        new Thread(() -> {
            final ArrayList<Item> found = new ArrayList<>();
            int[] types = {FileLoader.MEDIA_DIR_IMAGE, FileLoader.MEDIA_DIR_VIDEO, FileLoader.MEDIA_DIR_AUDIO, FileLoader.MEDIA_DIR_DOCUMENT};
            HashMap<String, Boolean> seen = new HashMap<>();
            for (int type : types) {
                try {
                    File dir = FileLoader.getDirectory(type);
                    File[] files = dir == null ? null : dir.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        if (!f.isFile() || seen.containsKey(f.getAbsolutePath())) continue;
                        String fn = f.getName();
                        String lower = fn.toLowerCase(Locale.ROOT);
                        if (fn.startsWith(".") || lower.endsWith(".temp") || lower.endsWith(".tmp") || lower.endsWith(".part") || f.length() <= 0) continue;
                        seen.put(f.getAbsolutePath(), true);
                        found.add(describe(f, type, names.get(f.getAbsolutePath())));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            Collections.sort(found, (a, b) -> Long.compare(b.date, a.date));
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                loading = false;
                all.clear();
                all.addAll(found);
                applyFilter();
            });
        }).start();
    }

    private Item describe(File f, int dirType, String originalName) {
        Item it = new Item();
        it.file = f;
        it.size = f.length();
        it.date = f.lastModified();
        String fn = f.getName();
        int dot = fn.lastIndexOf('.');
        it.ext = dot >= 0 && dot < fn.length() - 1 ? fn.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime = it.ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.ext);
        if (mime != null && mime.startsWith("image/")) it.kind = IMAGE;
        else if (mime != null && mime.startsWith("video/")) it.kind = VIDEO;
        else if (mime != null && mime.startsWith("audio/")) it.kind = AUDIO;
        else if (mime == null && dirType == FileLoader.MEDIA_DIR_IMAGE) it.kind = IMAGE;
        else if (mime == null && dirType == FileLoader.MEDIA_DIR_VIDEO) it.kind = VIDEO;
        else if (mime == null && dirType == FileLoader.MEDIA_DIR_AUDIO) it.kind = AUDIO;
        else it.kind = FILE;
        if (mime == null) mime = it.kind == IMAGE ? "image/*" : it.kind == VIDEO ? "video/*" : it.kind == AUDIO ? "audio/*" : "application/octet-stream";
        it.mime = mime;
        if (!TextUtils.isEmpty(originalName)) {
            it.name = originalName;
        } else {
            String label = it.kind == IMAGE ? "Photo" : it.kind == VIDEO ? "Video" : it.kind == AUDIO ? "Audio" : "File";
            it.name = label + " · " + new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(new Date(it.date));
        }
        return it;
    }

    private void applyFilter() {
        shown.clear();
        for (Item it : all) if (filter == ALL || it.kind == filter) shown.add(it);
        adapter.notifyDataSetChanged();
        renderHeader();
        renderState();
    }

    // ---------------------------------------------------------------- ui

    private int count(int kind) {
        if (kind == ALL) return all.size();
        int n = 0;
        for (Item it : all) if (it.kind == kind) n++;
        return n;
    }

    private void renderHeader() {
        long total = 0;
        for (Item it : all) total += it.size;
        summary.setText(loading ? "Looking for downloads…"
                : all.size() + (all.size() == 1 ? " file" : " files") + "  ·  " + AndroidUtilities.formatFileSize(total));
        chips.removeAllViews();
        final int accent = AeroPrefs.accent();
        for (int k = 0; k < FILTER_NAMES.length; k++) {
            final int kind = k;
            int n = count(k);
            if (k != ALL && n == 0) continue;
            boolean on = filter == k;
            TextView c = AeroUi.text(ctx, FILTER_NAMES[k] + (loading ? "" : "  " + n), 13.5f, on ? Color.WHITE : AeroPrivacyKit.ink(), true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(14), 0, dp(14), 0);
            c.setBackground(AeroUi.ripple(on ? AeroUi.round(accent, 18) : AeroUi.round(AeroPrivacyKit.surface(), 18), 18));
            c.setOnClickListener(v -> { filter = kind; applyFilter(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
        }
    }

    private void renderState() {
        stateHolder.removeAllViews();
        if (loading) {
            stateHolder.addView(AeroPrivacyKit.loading(ctx, AeroPrefs.accent()), new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        } else if (shown.isEmpty()) {
            stateHolder.addView(AeroPrivacyKit.message(ctx, R.drawable.aero_ic_download, AeroPrefs.accent(), "No downloads yet",
                    "Photos, videos, audio and files you download in chats will show up here.", null, null),
                    new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }
    }

    private static int kindColor(int kind) {
        switch (kind) {
            case IMAGE: return 0xFF2BA970;
            case VIDEO: return 0xFF8A5CD6;
            case AUDIO: return 0xFFE8903A;
            default: return AeroPrefs.accent();
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(4), dp(10));
            row.setBackground(AeroUi.ripple(AeroUi.round(AeroPrivacyKit.surface(), 16), 16));
            RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(8);
            row.setLayoutParams(rlp);

            FrameLayout tile = new FrameLayout(ctx);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            tile.setClipToOutline(true);
            ImageView thumb = new ImageView(ctx);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            TextView label = AeroUi.text(ctx, "", 12, Color.WHITE, true);
            label.setGravity(Gravity.CENTER);
            tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
            row.addView(tile, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = AeroUi.text(ctx, "", 15, AeroPrivacyKit.ink(), true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(name);
            TextView meta = AeroUi.text(ctx, "", 12.5f, AeroPrivacyKit.muted(), false);
            meta.setPadding(0, dp(2), 0, 0);
            meta.setSingleLine(true);
            texts.addView(meta);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(14);
            tl.rightMargin = dp(4);
            row.addView(texts, tl);

            FrameLayout more = new FrameLayout(ctx);
            more.setBackground(AeroUi.ripple(AeroUi.round(Color.TRANSPARENT, 20), 20));
            ImageView dots = new ImageView(ctx);
            dots.setImageResource(R.drawable.aero_ic_dots);
            dots.setColorFilter(AeroPrivacyKit.muted());
            more.addView(dots, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
            return new Holder(row, thumb, label, name, meta, more);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            final Item it = shown.get(position);
            final Holder v = (Holder) h;
            v.name.setText(it.name);
            v.meta.setText(AndroidUtilities.formatFileSize(it.size) + "  ·  " + AeroPrivacyKit.ago((int) (it.date / 1000L)));
            v.thumb.setImageDrawable(null);
            v.thumb.setBackgroundColor(AeroUi.alpha(kindColor(it.kind), 255));
            String ext = it.ext.isEmpty() ? "FILE" : it.ext.toUpperCase(Locale.ROOT);
            v.label.setText(it.kind == IMAGE || it.kind == VIDEO ? "" : (ext.length() > 4 ? ext.substring(0, 4) : ext));
            v.thumb.setTag(it.file.getAbsolutePath());
            if (it.kind == IMAGE || it.kind == VIDEO) loadThumb(it, v.thumb);
            v.itemView.setOnClickListener(x -> open(it));
            v.more.setOnClickListener(x -> menu(it));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView label, name, meta;
        final View more;

        Holder(View itemView, ImageView thumb, TextView label, TextView name, TextView meta, View more) {
            super(itemView);
            this.thumb = thumb;
            this.label = label;
            this.name = name;
            this.meta = meta;
            this.more = more;
        }
    }

    private void loadThumb(final Item it, final ImageView target) {
        final String key = it.file.getAbsolutePath();
        Bitmap cached = thumbs.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        try {
            thumbPool.execute(() -> {
                Bitmap b = null;
                try {
                    if (it.kind == IMAGE) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(key, o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= 160 && o.outHeight / (sample * 2) >= 160) sample *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        b = BitmapFactory.decodeFile(key, o);
                    } else {
                        MediaMetadataRetriever r = new MediaMetadataRetriever();
                        try {
                            r.setDataSource(key);
                            Bitmap frame = r.getFrameAtTime(0);
                            if (frame != null) {
                                float sc = 160f / Math.max(frame.getWidth(), frame.getHeight());
                                b = sc < 1f ? Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * sc)), Math.max(1, Math.round(frame.getHeight() * sc)), true) : frame;
                            }
                        } finally {
                            try { r.release(); } catch (Throwable ignore) { }
                        }
                    }
                } catch (Throwable e) {
                    // not decodable: the coloured tile stays
                }
                if (b == null) return;
                final Bitmap result = b;
                thumbs.put(key, result);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!destroyed && key.equals(target.getTag())) target.setImageBitmap(result);
                });
            });
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- actions

    private void menu(final Item it) {
        if (getParentActivity() == null) return;
        BottomSheet.Builder b = new BottomSheet.Builder(getParentActivity());
        b.setTitle(it.name, true);
        b.setItems(new CharSequence[]{"Save to phone", "Open", "Share", "Delete from app"},
                new int[]{R.drawable.aero_ic_download, R.drawable.aero_ic_external, R.drawable.aero_ic_share, R.drawable.aero_ic_trash},
                (dialog, which) -> {
                    if (which == 0) saveToPhone(it);
                    else if (which == 1) open(it);
                    else if (which == 2) share(it);
                    else confirmDelete(it);
                });
        showDialog(b.create());
    }

    private void open(Item it) {
        try {
            if (!it.file.exists()) {
                Toast.makeText(ctx, "File not found", Toast.LENGTH_SHORT).show();
                return;
            }
            AndroidUtilities.openForView(it.file, it.name, it.mime, getParentActivity(), null, false);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Couldn't open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void share(Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(it.mime);
            i.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, ApplicationLoader.getApplicationId() + ".provider", it.file));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivityForResult(Intent.createChooser(i, "Share"), 500);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Couldn't share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final Item it) {
        new AlertDialog.Builder(ctx)
                .setTitle("Delete from app?")
                .setMessage("This removes the downloaded copy from AeroGram's storage. The message stays in the chat and you can download it again. Copies you saved to your phone are not affected.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (it.file.delete()) {
                        all.remove(it);
                        applyFilter();
                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ctx, "Couldn't delete this file", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    /** Copies the file into the phone's shared storage (Downloads / Pictures / Movies / Music, in an "AeroGram" folder). */
    private void saveToPhone(final Item it) {
        final Context app = ApplicationLoader.applicationContext;
        final String saveName = saveName(it);
        Toast.makeText(ctx, "Saving…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String where = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver r = app.getContentResolver();
                    String folder;
                    Uri base;
                    if (it.kind == IMAGE) { folder = Environment.DIRECTORY_PICTURES; base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; }
                    else if (it.kind == VIDEO) { folder = Environment.DIRECTORY_MOVIES; base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; }
                    else if (it.kind == AUDIO) { folder = Environment.DIRECTORY_MUSIC; base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; }
                    else { folder = Environment.DIRECTORY_DOWNLOADS; base = MediaStore.Downloads.EXTERNAL_CONTENT_URI; }
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, saveName);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/AeroGram");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = r.insert(base, v);
                    if (uri == null) throw new IllegalStateException("insert failed");
                    try (InputStream in = new FileInputStream(it.file); OutputStream out = r.openOutputStream(uri)) {
                        copy(in, out);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    r.update(uri, done, null, null);
                    where = folder + "/AeroGram";
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroGram");
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed");
                    File target = new File(dir, saveName);
                    int n = 1;
                    while (target.exists()) {
                        int dot = saveName.lastIndexOf('.');
                        String base = dot > 0 ? saveName.substring(0, dot) : saveName;
                        String ext = dot > 0 ? saveName.substring(dot) : "";
                        target = new File(dir, base + " (" + (n++) + ")" + ext);
                    }
                    try (InputStream in = new FileInputStream(it.file); OutputStream out = new FileOutputStream(target)) {
                        copy(in, out);
                    }
                    MediaScannerConnection.scanFile(app, new String[]{target.getAbsolutePath()}, new String[]{it.mime}, null);
                    where = "Download/AeroGram";
                }
            } catch (Throwable e) {
                FileLog.e(e);
                where = null;
            }
            final String result = where;
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(app,
                    result != null ? "Saved to " + result : "Couldn't save to your phone. Check storage permission and free space.",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static String saveName(Item it) {
        String n = it.name;
        boolean generic = n.startsWith("Photo ·") || n.startsWith("Video ·") || n.startsWith("Audio ·") || n.startsWith("File ·");
        String ext = it.ext.isEmpty() ? "" : "." + it.ext;
        if (generic) {
            String prefix = it.kind == IMAGE ? "IMG" : it.kind == VIDEO ? "VID" : it.kind == AUDIO ? "AUD" : "FILE";
            return "AeroGram_" + prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(it.date)) + ext;
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!ext.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith(ext)) n += ext;
        return n;
    }
}
