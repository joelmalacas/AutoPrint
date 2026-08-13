package com.example.autoprint;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ecrã de gestão de templates: permite ao utilizador escolher uma imagem da galeria,
 * guarda-a na pasta privada da app (com o nome sanitizado) e deixa escolher qual
 * dos templates guardados está ativo para ser usado na impressão.
 */
public class TemplateActivity extends AppCompatActivity {

    private static final String TAG = "AutoPrintDebug";
    private static final String TEMPLATES_DIR_NAME = "templates";

    private LinearLayout templatesContainer;
    private TextView txtEmptyState;
    private SharedPreferences prefs;
    private File templatesDir;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importTemplate(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template);

        prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        templatesDir = new File(getFilesDir(), TEMPLATES_DIR_NAME);
        if (!templatesDir.exists()) {
            templatesDir.mkdirs();
        }

        templatesContainer = findViewById(R.id.templatesContainer);
        txtEmptyState = findViewById(R.id.txtEmptyState);
        ImageButton btnBack = findViewById(R.id.btnBack);
        View btnAddTemplate = findViewById(R.id.btnAddTemplate);

        btnBack.setOnClickListener(v -> finish());
        btnAddTemplate.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    // ================= LISTAGEM =================

    private void refreshList() {
        templatesContainer.removeAllViews();

        File[] files = templatesDir.listFiles();
        if (files == null || files.length == 0) {
            txtEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        txtEmptyState.setVisibility(View.GONE);

        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparing(File::getName));

        String selectedPath = prefs.getString(DefActivity.KEY_SELECTED_TEMPLATE, "");

        LayoutInflater inflater = LayoutInflater.from(this);
        for (File file : sorted) {
            View row = inflater.inflate(R.layout.item_template, templatesContainer, false);

            ImageView imgThumb = row.findViewById(R.id.imgThumb);
            TextView txtName = row.findViewById(R.id.txtName);
            TextView txtSelected = row.findViewById(R.id.txtSelected);
            ImageButton btnDelete = row.findViewById(R.id.btnDelete);

            txtName.setText(file.getName());

            // Miniatura reduzida, para não gastar memória à toa numa lista
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            Bitmap thumb = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (thumb != null) {
                imgThumb.setImageBitmap(thumb);
            }

            boolean isSelected = file.getAbsolutePath().equals(selectedPath);
            txtSelected.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            row.setOnClickListener(v -> selectTemplate(file));
            btnDelete.setOnClickListener(v -> deleteTemplate(file));

            templatesContainer.addView(row);
        }
    }

    private void selectTemplate(File file) {
        prefs.edit().putString(DefActivity.KEY_SELECTED_TEMPLATE, file.getAbsolutePath()).apply();
        Toast.makeText(this, "Template selecionado: " + file.getName(), Toast.LENGTH_SHORT).show();
        refreshList();
    }

    private void deleteTemplate(File file) {
        String selectedPath = prefs.getString(DefActivity.KEY_SELECTED_TEMPLATE, "");
        boolean wasSelected = file.getAbsolutePath().equals(selectedPath);

        if (file.delete() && wasSelected) {
            prefs.edit().remove(DefActivity.KEY_SELECTED_TEMPLATE).apply();
        }
        refreshList();
    }

    // ================= IMPORTAÇÃO =================

    private void importTemplate(Uri sourceUri) {
        new Thread(() -> {
            try {
                String originalName = queryDisplayName(sourceUri);
                String sanitized = sanitizeFileName(originalName);
                File destFile = uniqueFile(templatesDir, sanitized);

                try (InputStream in = getContentResolver().openInputStream(sourceUri);
                     OutputStream out = new FileOutputStream(destFile)) {
                    if (in == null) throw new java.io.IOException("Não foi possível abrir a imagem");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "Template adicionado: " + destFile.getName(), Toast.LENGTH_SHORT).show();
                    refreshList();
                });
            } catch (Exception e) {
                Log.e(TAG, "Erro ao importar template", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Erro ao importar template: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // Lê o nome original do ficheiro escolhido na galeria (o content:// Uri não dá isso diretamente)
    private String queryDisplayName(Uri uri) {
        String result = "template.jpg";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        result = name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Se não conseguir ler o nome, usa o valor por defeito
        }
        return result;
    }

    // Regras: só letras minúsculas, números e underscore; espaços viram underscore
    private String sanitizeFileName(String original) {
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot + 1) : "jpg";

        base = base.trim().toLowerCase(Locale.US);
        base = base.replaceAll("\\s+", "_");           // espaços -> underscore
        base = base.replaceAll("[^a-z0-9_]", "");       // remove tudo o resto que não seja permitido

        extension = extension.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");

        if (base.isEmpty()) base = "template";
        if (extension.isEmpty()) extension = "jpg";

        return base + "." + extension;
    }

    // Evita substituir um template existente com o mesmo nome — acrescenta _1, _2, etc.
    private File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";

        int counter = 1;
        do {
            candidate = new File(dir, base + "_" + counter + extension);
            counter++;
        } while (candidate.exists());

        return candidate;
    }
}