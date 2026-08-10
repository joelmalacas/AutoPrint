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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;

    private static final int MAX_PHOTOS = 10;
    // CORREÇÃO 1: Removida a barra final para bater certo com a gravação no MediaStore
    private static final String PHOTOS_RELATIVE_PATH = "Pictures/AutoPrint";

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

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());

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
                        playCaptureFlashAnimation();
                        Uri savedUri = output.getSavedUri();
                        if (savedUri != null) {
                            imgThumbnail.setImageURI(savedUri);
                        }
                        Toast.makeText(MainActivity.this,
                                "Fotografia guardada", Toast.LENGTH_SHORT).show();

                        enforcePhotoLimit();

                        if (savedUri != null) {
                            printPhoto(savedUri);
                        }
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

    // ================= IMPRESSÃO AUTOMÁTICA =================

    private void printPhoto(Uri photoUri) {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        String ip = prefs.getString(DefActivity.KEY_PRINTER_IP, "");
        int port = prefs.getInt(DefActivity.KEY_PRINTER_PORT, 631); // CORREÇÃO: Porta padrão IPP 631

        if (TextUtils.isEmpty(ip)) {
            txtStatus.setText("Impressora não configurada");
            return;
        }

        txtStatus.setText("A imprimir...");

        new Thread(() -> {
            Bitmap bitmap = loadBitmapFromUri(photoUri);
            if (bitmap == null) {
                runOnUiThread(() -> txtStatus.setText("Erro ao carregar foto"));
                return;
            }

            PrinterHelper.printImage(ip, port, bitmap, new PrinterHelper.PrintCallback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        txtStatus.setText("Impressão enviada");
                        Toast.makeText(MainActivity.this, "Foto enviada para a HP LaserJet", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String message) {
                    Log.e("AutoPrintDebug", message);
                    runOnUiThread(() -> {
                        txtStatus.setText("Falha ao imprimir (ver Logcat)");
                        Toast.makeText(MainActivity.this, "Erro: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        }).start();
    }

    // CORREÇÃO 2: Carregamento seguro da imagem compatível com todas as versões Android
    private Bitmap loadBitmapFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setTargetSampleSize(2); // Reduz consumo de RAM
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

    private void enforcePhotoLimit() {
        new Thread(() -> {
            // Em versões anteriores ao Android 10 (API 29), a coluna RELATIVE_PATH não existe no MediaStore
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

        btnFlash.setImageResource(R.drawable.ic_flash_off);
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