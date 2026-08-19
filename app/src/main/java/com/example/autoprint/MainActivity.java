package com.example.autoprint;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private ImageButton btnCapture;
    private ImageButton btnFlash;
    private FloatingActionButton btnSwitchCamera;
    private ImageView imgThumbnail;
    private TextView txtStatus;
    private android.view.View captureFlashOverlay;
    private GridOverlayView gridOverlay;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;

    private static final int MAX_PHOTOS = 10;
    private static final String PHOTOS_RELATIVE_PATH = "Pictures/AutoPrint";

    // Som do "click" do obturador — carregado antecipadamente para não ter atraso na primeira foto
    private MediaActionSound shutterSound;

    // Histórico de impressões (timestamp, foto, template usado)
    private PrintHistoryDbHelper printHistoryDb;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this,
                            "É necessária a permissão da câmara para usar a app",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        btnCapture = findViewById(R.id.btnCapture);
        btnFlash = findViewById(R.id.btnFlash);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        imgThumbnail = findViewById(R.id.imgThumbnail);
        txtStatus = findViewById(R.id.txtStatus);
        captureFlashOverlay = findViewById(R.id.captureFlashOverlay);
        gridOverlay = findViewById(R.id.gridOverlay);

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        btnCapture.setOnClickListener(v -> takePhoto());
        btnFlash.setOnClickListener(v -> toggleFlash());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
                startActivity(new android.content.Intent(MainActivity.this, DefActivity.class)));

        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);

        printHistoryDb = new PrintHistoryDbHelper(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Atualiza a grelha ao voltar das Definições, caso o utilizador a tenha alternado lá
        updateGridVisibility();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shutterSound != null) {
            shutterSound.release();
        }
    }

    private void updateGridVisibility() {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        boolean showGrid = prefs.getBoolean(DefActivity.KEY_SHOW_GRID, false);
        gridOverlay.setVisibility(showGrid ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ================= CÂMARA =================
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                txtStatus.setText("Erro ao iniciar a câmara");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setFlashMode(flashMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture);
            txtStatus.setText("A câmara está pronta");
        } catch (Exception exc) {
            txtStatus.setText("Erro ao iniciar a câmara");
        }
    }

    // ================= CAPTURA DE FOTO =================

    private void takePhoto() {
        if (imageCapture == null) return;

        if (isShutterSoundEnabled()) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK);
        }

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());

        if (isSaveToGalleryEnabled()) {
            takePhotoToGallery(name);
        } else {
            takePhotoToPrivateStorage(name);
        }
    }

    // Lê a preferência definida em Definições > "Som do obturador"
    private boolean isShutterSoundEnabled() {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(DefActivity.KEY_SHUTTER_SOUND, true);
    }

    // Lê a preferência definida em Definições > "Guardar na galeria"
    private boolean isSaveToGalleryEnabled() {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(DefActivity.KEY_SAVE_GALLERY, true);
    }

    // Guarda a foto na Galeria pública do telemóvel (Pictures/AutoPrint), visível na app Fotos
    private void takePhotoToGallery(String name) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, PHOTOS_RELATIVE_PATH);
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        ).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        onPhotoCaptured(output.getSavedUri(), /* savedToGallery = */ true);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this,
                                "Falha ao guardar a fotografia: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    // Guarda a foto só dentro da pasta privada da app — não aparece na Galeria/Fotos do
    // telemóvel, mas continua acessível para a miniatura e para a impressão via FileProvider
    private void takePhotoToPrivateStorage(String name) {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AutoPrint");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File photoFile = new File(dir, "IMG_" + name + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Uri contentUri = FileProvider.getUriForFile(
                                MainActivity.this,
                                getPackageName() + ".fileprovider",
                                photoFile);
                        onPhotoCaptured(contentUri, /* savedToGallery = */ false);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this,
                                "Falha ao guardar a fotografia: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    // Fluxo comum depois de qualquer foto ser guardada, seja na galeria ou em privado
    private void onPhotoCaptured(Uri savedUri, boolean savedToGallery) {
        playCaptureFlashAnimation();

        if (savedUri != null) {
            imgThumbnail.setImageURI(savedUri);
        }

        Toast.makeText(this,
                savedToGallery ? "Fotografia guardada na galeria" : "Fotografia guardada na app",
                Toast.LENGTH_SHORT).show();

        if (savedToGallery) {
            enforcePhotoLimitGallery();
        } else {
            enforcePhotoLimitPrivateStorage();
        }

        if (savedUri != null) {
            printPhoto(savedUri);
        }
    }

    // ================= IMPRESSÃO AUTOMÁTICA =================

    // Lê o caminho do template escolhido em Definições > Gerir templates (string vazia se nenhum)
    private String getSelectedTemplatePath() {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(DefActivity.KEY_SELECTED_TEMPLATE, "");
    }

    // Carrega o Bitmap do template a partir do caminho guardado. Devolve null se ainda não
    // houver nenhum escolhido (ou o ficheiro já não existir), caso em que o chamador deve
    // usar o template fixo da app como reserva.
    private Bitmap loadSelectedTemplateBitmap(String path) {
        if (TextUtils.isEmpty(path)) return null;

        File file = new File(path);
        if (!file.exists()) return null;

        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inScaled = false;
        return android.graphics.BitmapFactory.decodeFile(path, options);
    }

    private void printPhoto(Uri photoUri) {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        String ip = prefs.getString(DefActivity.KEY_PRINTER_IP, "");
        int port = prefs.getInt(DefActivity.KEY_PRINTER_PORT, 8000);
        String printerName = prefs.getString(DefActivity.KEY_PRINTER_NAME, "");

        if (TextUtils.isEmpty(ip)) {
            txtStatus.setText("Impressora não configurada");
            return;
        }

        txtStatus.setText("A gerar template e imprimir...");

        new Thread(() -> {
            try {
                // 1. Carrega o Bitmap original da câmara
                Bitmap rawPhotoBitmap = loadBitmapFromUri(photoUri);
                if (rawPhotoBitmap == null) {
                    runOnUiThread(() -> txtStatus.setText("Erro ao carregar foto"));
                    return;
                }

                // 2. Escolhe o template: o que foi selecionado em Definições > Gerir templates,
                //    ou o template fixo da app caso ainda não tenha sido escolhido nenhum
                String selectedTemplatePath = getSelectedTemplatePath();
                Bitmap selectedTemplate = loadSelectedTemplateBitmap(selectedTemplatePath);
                Bitmap journalBitmap = (selectedTemplate != null)
                        ? TemplateComposer.processJournalTemplate(rawPhotoBitmap, selectedTemplate)
                        : TemplateComposer.processJournalTemplate(
                        MainActivity.this,
                        rawPhotoBitmap,
                        R.drawable.retro_paparazzi_template_cabo_verde
                );

                // Regista no histórico apenas quando a impressão for bem-sucedida
                String templateLabel = (selectedTemplate != null) ? selectedTemplatePath : "template_fixo";
                long captureTimestamp = System.currentTimeMillis();

                if (journalBitmap == null) {
                    runOnUiThread(() -> txtStatus.setText("Erro ao processar template"));
                    return;
                }

                // 3. Envia para a impressora, através da API de impressão Windows
                PrinterHelper.printImage(ip, port, journalBitmap, printerName, new PrinterHelper.PrintCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> txtStatus.setText("Impressão enviada"));

                        // Só grava no histórico (local e remoto) depois de confirmar que imprimiu
                        printHistoryDb.insertRecord(captureTimestamp, photoUri.toString(), templateLabel);
                        new Thread(() ->
                                MySqlHelper.insertPrintRecord(captureTimestamp, photoUri.toString(), templateLabel)
                        ).start();
                    }

                    @Override
                    public void onError(String message) {
                        Log.e("AutoPrintDebug", message);
                        runOnUiThread(() -> txtStatus.setText("Falha ao imprimir"));
                    }
                });

            } catch (Throwable e) {
                // Captura tanto Exception como OutOfMemoryError sem crashar a app
                Log.e("AutoPrintCrash", "Erro ao processar/imprimir template: ", e);
                runOnUiThread(() -> {
                    txtStatus.setText("Erro no processamento do template");
                    Log.e("AutoPrint", "Erro: " + e.getLocalizedMessage());
                });
            }
        }).start();
    }

    private Bitmap loadBitmapFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setTargetSampleSize(2); // Reduz consumo de RAM
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); //Alloc BitMap
                });
            } else {
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    return BitmapFactory.decodeStream(inputStream, null, options);
                }
            }
        } catch (Exception e) {
            Log.e("AutoPrint", "Erro ao carregar bitmap: " + e.getMessage());
            return null;
        }
    }

    // ================= LIMITE DE FOTOGRAFIAS GUARDADAS =================

    // Versão para quando as fotos vão para a Galeria pública (MediaStore)
    private void enforcePhotoLimitGallery() {
        new Thread(() -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = { MediaStore.Images.Media._ID };

            // Procura correspondências com ou sem a barra no final
            String selection = MediaStore.Images.Media.RELATIVE_PATH + "=? OR " +
                    MediaStore.Images.Media.RELATIVE_PATH + "=?";
            String[] selectionArgs = { PHOTOS_RELATIVE_PATH, PHOTOS_RELATIVE_PATH + "/" };
            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " ASC";

            List<Uri> allPhotos = new ArrayList<>();

            try (Cursor cursor = getContentResolver().query(
                    collection, projection, selection, selectionArgs, sortOrder)) {

                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        allPhotos.add(ContentUris.withAppendedId(collection, id));
                    }
                }
            } catch (Exception e) {
                Log.e("AutoPrint", "Erro ao consultar MediaStore: " + e.getMessage());
                return;
            }

            int excess = allPhotos.size() - MAX_PHOTOS;
            if (excess <= 0) return;

            for (int i = 0; i < excess; i++) {
                try {
                    getContentResolver().delete(allPhotos.get(i), null, null);
                } catch (SecurityException se) {
                    Log.w("AutoPrint", "Sem permissão para eliminar foto antiga: " + se.getMessage());
                }
            }
        }).start();
    }

    // Versão para quando as fotos ficam guardadas apenas na pasta privada da app
    // (fora do MediaStore, por isso lê e apaga diretamente os ficheiros do disco)
    private void enforcePhotoLimitPrivateStorage() {
        new Thread(() -> {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AutoPrint");
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_PHOTOS) return;

            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort(Comparator.comparingLong(File::lastModified)); // mais antigos primeiro

            int excess = sorted.size() - MAX_PHOTOS;
            for (int i = 0; i < excess; i++) {
                sorted.get(i).delete();
            }
        }).start();
    }

    private void playCaptureFlashAnimation() {
        captureFlashOverlay.setVisibility(android.view.View.VISIBLE);
        captureFlashOverlay.setAlpha(0.85f);
        captureFlashOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> captureFlashOverlay.setVisibility(android.view.View.GONE))
                .start();
    }

    // ================= FLASH =================
    private void toggleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_ON;
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
        } else {
            flashMode = ImageCapture.FLASH_MODE_OFF;
        }

        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }

        switch (flashMode) {
            case ImageCapture.FLASH_MODE_ON:
                btnFlash.setImageResource(R.drawable.ic_flash_on);
                break;

            case ImageCapture.FLASH_MODE_AUTO:
                btnFlash.setImageResource(R.drawable.ic_flash_auto);
                break;

            case ImageCapture.FLASH_MODE_OFF:
            default:
                btnFlash.setImageResource(R.drawable.ic_flash_off);
                break;
        }
    }

    // ================= TROCAR CÂMARA =================
    private void switchCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        bindCameraUseCases();
    }
}