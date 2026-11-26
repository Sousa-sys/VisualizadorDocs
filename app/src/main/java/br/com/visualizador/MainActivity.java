package br.com.visualizador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PICK = 1, SAVE = 2;
    private Button edit, save;
    private Editor editor;           // não nulo no modo de edição
    private String name = "";
    private byte[] pending;          // conteúdo aguardando o local para salvar
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout content;
    private Uri current;
    private int generation;          // descarta resultados de aberturas antigas
    private PdfRenderer pdf;         // usado somente na thread "worker"
    private WebView web;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        Button open = new Button(this);
        open.setText("Abrir documento");
        open.setOnClickListener(v -> confirmDiscard(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK);
        }));
        edit = new Button(this);
        edit.setText("Editar");
        edit.setEnabled(false);
        edit.setOnClickListener(v -> startEditing());
        save = new Button(this);
        save.setText("Salvar como");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveCopy());
        Button history = new Button(this);
        history.setText("Histórico");
        history.setOnClickListener(v -> confirmDiscard(this::showHistory));
        LinearLayout bar = new LinearLayout(this);
        for (Button b : new Button[] {open, history, edit, save}) bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        content = new FrameLayout(this);
        root.addView(bar);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        String saved = state == null ? null : state.getString("uri");
        if (saved != null) load(Uri.parse(saved));
        else if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null)
            load(getIntent().getData());
        else message("Toque em \"Abrir documento\".\n\nPDF, Word, Excel, PowerPoint, OpenDocument, EPUB, "
            + "RTF, CSV, Markdown, texto, HTML, imagens e ZIP.\n\nEdição: texto, CSV, Word, Excel, "
            + "PowerPoint e OpenDocument.");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK || request == SAVE) keepAccess(data);
        if (request == PICK) load(data.getData());
        else if (request == SAVE && pending != null) write(data.getData(), pending);
        else if (request == SAVE) toast("Não foi salvo: o app foi reiniciado. Edite novamente.");
    }

    @Override public void onBackPressed() {
        if (editor != null) confirmDiscard(() -> load(current));
        else super.onBackPressed();
    }

    private void confirmDiscard(Runnable then) {
        if (editor == null || !editor.changed()) { then.run(); return; }
        new AlertDialog.Builder(this).setMessage("Descartar as alterações não salvas?")
            .setPositiveButton("Descartar", (d, w) -> then.run())
            .setNegativeButton("Continuar editando", null).show();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return Converter.readAll(in, Converter.MAX_BYTES);
        }
    }

    private void startEditing() {
        Uri uri = current;
        String file = name;
        int mine = ++generation;
        edit.setEnabled(false);
        message("Preparando a edição…");
        worker.execute(() -> {
            try {
                Editor ed = new Editor(file, read(uri));
                runOnUiThread(() -> { if (mine == generation) showEditor(ed); });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    if (mine != generation) return;
                    load(uri);
                    toast("Não foi possível editar: " + e.getMessage());
                });
            }
        });
    }

    private void showEditor(Editor ed) {
        editor = ed;
        save.setEnabled(true);
        if (ed.plainText) {
            EditText text = new EditText(this);
            text.setGravity(Gravity.TOP);
            text.setText(ed.fields.get(0).value);
            text.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) { ed.fields.get(0).value = s.toString(); }
            });
            replace(text);
            return;
        }
        // Documentos estruturados: toque em um item para alterá-lo.
        ListView list = new ListView(this);
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return ed.fields.size(); }
            public Object getItem(int i) { return ed.fields.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View recycled, ViewGroup parent) {
                TextView t = recycled != null ? (TextView) recycled : new TextView(MainActivity.this);
                Editor.Field f = ed.fields.get(i);
                t.setPadding(32, 24, 32, 24);
                t.setTextSize(16);
                t.setText(f.label + (f.value.equals(f.original) ? "" : "  (alterado)") + "\n" + f.value);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Editor.Field f = ed.fields.get(position);
            EditText input = new EditText(this);
            input.setText(f.value);
            new AlertDialog.Builder(this).setTitle(f.label).setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String typed = input.getText().toString();
                    f.value = f.original.contains("\n") ? typed : typed.replace('\n', ' ');
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Cancelar", null).show();
        });
        replace(list);
    }

    /** Sempre grava uma cópia em local escolhido pelo usuário. */
    private void saveCopy() {
        Editor ed = editor;
        if (ed == null) return;
        int mine = generation;
        save.setEnabled(false);
        worker.execute(() -> {
            try {
                byte[] bytes = ed.save();
                runOnUiThread(() -> {
                    if (mine != generation) return;
                    save.setEnabled(true);
                    pending = bytes;
                    chooseDestination();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    save.setEnabled(true);
                    toast("Falha ao salvar: " + e.getMessage());
                });
            }
        });
    }

    private void chooseDestination() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String type = getContentResolver().getType(current);
        i.setType(type == null ? "application/octet-stream" : type);
        int dot = name.lastIndexOf('.');
        i.putExtra(Intent.EXTRA_TITLE, name.substring(0, dot) + " (editado)" + name.substring(dot));
        startActivityForResult(i, SAVE);
    }

    private void write(Uri target, byte[] bytes) {
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                out.write(bytes);
            } catch (Throwable e) {
                runOnUiThread(() -> toast("Falha ao salvar: " + e.getMessage()));
                return;
            }
            runOnUiThread(() -> {
                pending = null;
                toast("Cópia salva");
                load(target);
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (current != null) out.putString("uri", current.toString());
    }

    @Override protected void onDestroy() {
        worker.execute(this::closePdf);
        worker.shutdown();
        destroyWeb();
        super.onDestroy();
    }

    /** Mantém a permissão de leitura para reabrir o arquivo pelo histórico. */
    private void keepAccess(Intent data) {
        try {
            getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // O provedor não oferece permissão permanente.
        }
    }

    /** Cada item é "uri\tnome"; o mais recente vem primeiro. */
    private List<String> recent() {
        String saved = getPreferences(MODE_PRIVATE).getString("recent", "");
        List<String> items = new ArrayList<>();
        for (String line : saved.split("\n")) if (line.contains("\t")) items.add(line);
        return items;
    }

    private void remember(Uri uri, String file) {
        List<String> items = recent();
        items.removeIf(line -> line.startsWith(uri + "\t"));
        items.add(0, uri + "\t" + file.replace('\n', ' ').replace('\t', ' '));
        if (items.size() > 30) items.subList(30, items.size()).clear();
        getPreferences(MODE_PRIVATE).edit().putString("recent", String.join("\n", items)).apply();
    }

    private void showHistory() {
        generation++;
        editor = null;
        current = null;
        edit.setEnabled(false);
        save.setEnabled(false);
        setTitle("Histórico");
        List<String> items = recent();
        if (items.isEmpty()) { message("Nenhum documento aberto ainda."); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Button clear = new Button(this);
        clear.setText("Limpar histórico");
        clear.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().remove("recent").apply();
            showHistory();
        });
        ListView list = new ListView(this);
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View recycled, ViewGroup parent) {
                TextView t = recycled != null ? (TextView) recycled : new TextView(MainActivity.this);
                t.setPadding(32, 32, 32, 32);
                t.setTextSize(16);
                t.setText(items.get(i).substring(items.get(i).indexOf('\t') + 1));
                return t;
            }
        });
        list.setOnItemClickListener((parent, view, i, id) ->
            load(Uri.parse(items.get(i).substring(0, items.get(i).indexOf('\t')))));
        box.addView(clear);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        replace(box);
    }

    private void load(Uri uri) {
        current = uri;
        int mine = ++generation;
        editor = null;
        edit.setEnabled(false);
        save.setEnabled(false);
        message("Abrindo…");
        worker.execute(() -> {
            try {
                closePdf();
                String name = displayName(uri);
                runOnUiThread(() -> {
                    if (mine != generation) return;
                    this.name = name;
                    setTitle(name);
                    edit.setEnabled(Editor.supports(name));
                });
                String type = getContentResolver().getType(uri);
                if (Converter.extension(name).equals("pdf") || "application/pdf".equals(type)) {
                    openPdf(uri);
                    int pages = pdf.getPageCount();
                    runOnUiThread(() -> { if (mine != generation) return;
                        remember(uri, name);
                        showPdf(pages, mine);
                    });
                } else {
                    String html = Converter.toHtml(name, read(uri));
                    runOnUiThread(() -> {
                        if (mine != generation) return;
                        remember(uri, name);
                        showHtml(html);
                    });
                }
            } catch (Throwable e) {
                String why = e instanceof SecurityException ? "Sem acesso ao arquivo (ou PDF com senha)."
                    : e instanceof OutOfMemoryError ? "Memória insuficiente para este arquivo."
                    : String.valueOf(e.getMessage());
                runOnUiThread(() -> { if (mine == generation) message("Não foi possível abrir.\n\n" + why); });
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "documento" : last;
    }

    /** Copia para o cache: PdfRenderer exige um arquivo com acesso aleatório. */
    private void openPdf(Uri uri) throws IOException {
        File copy = File.createTempFile("doc", ".pdf", getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(copy)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
        // O descritor continua válido depois de apagar o arquivo temporário.
        copy.delete();
        try { pdf = new PdfRenderer(fd); }
        catch (IOException | RuntimeException e) { fd.close(); throw e; }
    }

    private void closePdf() {
        if (pdf != null) { pdf.close(); pdf = null; }
    }

    private void replace(View v) {
        pinch = null;
        destroyWeb();
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void destroyWeb() {
        if (web != null) { content.removeView(web); web.destroy(); web = null; }
    }

    private void message(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(48, 48, 48, 48);
        replace(t);
    }

    private void showHtml(String html) {
        WebView v = new WebView(this);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        v.setWebViewClient(new WebViewClient() {
            // Links de documentos não são abertos.
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r) {
                if ("data".equals(r.getUrl().getScheme())) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        replace(v);
        web = v;
        // loadDataWithBaseURL aceita conteúdo bem maior que loadData.
        v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private float zoom = 1;
    private ScaleGestureDetector pinch;   // não nulo enquanto um PDF está na tela

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (pinch != null) pinch.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private void showPdf(int pages, int mine) {
        zoom = 1;
        ListView list = new ListView(this);
        list.setBackgroundColor(Color.DKGRAY);
        list.setDividerHeight(12);
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return pages; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int position, View recycled, ViewGroup parent) {
                ImageView image = recycled != null ? (ImageView) recycled : new ImageView(MainActivity.this);
                image.setAdjustViewBounds(true);
                image.setMinimumHeight(600);
                image.setImageDrawable(null);
                image.setTag(position);
                int width = Math.max(1, Math.min(parent.getWidth(), 2000));
                worker.execute(() -> {
                    if (mine != generation || pdf == null || !Integer.valueOf(position).equals(image.getTag())) return;
                    try (PdfRenderer.Page p = pdf.openPage(position)) {
                        int height = Math.max(1, Math.min(width * p.getHeight() / p.getWidth(), 4000));
                        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        runOnUiThread(() -> {
                            if (Integer.valueOf(position).equals(image.getTag())) image.setImageBitmap(b);
                        });
                    } catch (RuntimeException | OutOfMemoryError e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Falha na página " + (position + 1), Toast.LENGTH_SHORT).show());
                    }
                });
                return image;
            }
        });
        // A largura da lista cresce com o zoom; a rolagem lateral mostra o restante.
        FrameLayout page = new FrameLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                int wide = Math.round(getResources().getDisplayMetrics().widthPixels * zoom);
                super.onMeasure(MeasureSpec.makeMeasureSpec(wide, MeasureSpec.EXACTLY), height);
            }
        };
        page.addView(list, new FrameLayout.LayoutParams(-1, -1));
        HorizontalScrollView side = new HorizontalScrollView(this);
        side.addView(page, new FrameLayout.LayoutParams(-2, -1));

        java.util.function.Consumer<Float> setZoom = value -> {
            zoom = Math.max(1f, Math.min(value, 4f));
            page.requestLayout();
            // Redesenha as páginas depois do novo tamanho, para ficarem nítidas.
            page.post(((BaseAdapter) list.getAdapter())::notifyDataSetChanged);
        };
        LinearLayout buttons = new LinearLayout(this);
        String[] labels = {"Zoom −", "Zoom +"};
        for (int i = 0; i < 2; i++) {
            float factor = i == 0 ? 0.8f : 1.25f;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setOnClickListener(v -> setZoom.accept(zoom * factor));
            buttons.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(side, new LinearLayout.LayoutParams(-1, 0, 1));
        box.addView(buttons);
        replace(box);
        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float scale = 1;
            @Override public boolean onScale(ScaleGestureDetector d) { scale *= d.getScaleFactor(); return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d) { setZoom.accept(zoom * scale); scale = 1; }
        });
    }
}
