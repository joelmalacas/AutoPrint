package com.example.autoprint;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Ajuda a enviar uma fotografia para uma impressora térmica de rede (protocolo ESC/POS,
 * normalmente na porta 9100 - "RAW"/JetDirect). Funciona com a maioria das impressoras
 * térmicas de talões/etiquetas que suportam o comando de imagem raster GS v 0.
 */
public class PrinterHelper {

    // Largura de impressão em pontos. 384 pontos ~= impressoras térmicas de 58mm (203dpi).
    // Se a tua impressora for de 80mm, experimenta 576.
    private static final int PRINTER_WIDTH_DOTS = 384;

    private static final int SOCKET_TIMEOUT_MS = 5000;

    public interface PrintCallback {
        void onSuccess();
        void onError(String message);
    }

    /**
     * Envia o bitmap para impressão. Deve ser chamado numa thread que NÃO seja a principal,
     * porque abre uma ligação de rede.
     */
    public static void printImage(String ip, int port, Bitmap bitmap, PrintCallback callback) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();

            Bitmap scaled = scaleToPrinterWidth(bitmap);
            boolean[][] blackPixels = ditherToBlackAndWhite(scaled);

            byte[] payload = buildEscPosPayload(blackPixels, scaled.getWidth(), scaled.getHeight());

            out.write(payload);
            out.flush();

            if (!scaled.isRecycled() && scaled != bitmap) {
                scaled.recycle();
            }

            if (callback != null) callback.onSuccess();
        } catch (IOException e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    // ================= PREPARAÇÃO DA IMAGEM =================

    // Reduz a foto à largura que a impressora consegue imprimir, mantendo a proporção
    private static Bitmap scaleToPrinterWidth(Bitmap original) {
        if (original.getWidth() <= PRINTER_WIDTH_DOTS) {
            return original;
        }
        float ratio = (float) PRINTER_WIDTH_DOTS / original.getWidth();
        int targetHeight = Math.round(original.getHeight() * ratio);
        return Bitmap.createScaledBitmap(original, PRINTER_WIDTH_DOTS, targetHeight, true);
    }

    // Converte a foto a cores/tons de cinzento para preto e branco puro, usando dithering
    // (Floyd-Steinberg) para que a foto impressa mantenha detalhe em vez de ficar "queimada".
    private static boolean[][] ditherToBlackAndWhite(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float[][] gray = new float[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                float luminance = 0.299f * Color.red(pixel)
                        + 0.587f * Color.green(pixel)
                        + 0.114f * Color.blue(pixel);
                gray[y][x] = luminance;
            }
        }

        boolean[][] black = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float oldPixel = gray[y][x];
                float newPixel = oldPixel < 128 ? 0 : 255;
                black[y][x] = newPixel == 0; // true = imprimir ponto preto

                float error = oldPixel - newPixel;

                if (x + 1 < width) gray[y][x + 1] += error * 7f / 16f;
                if (y + 1 < height) {
                    if (x - 1 >= 0) gray[y + 1][x - 1] += error * 3f / 16f;
                    gray[y + 1][x] += error * 5f / 16f;
                    if (x + 1 < width) gray[y + 1][x + 1] += error / 16f;
                }
            }
        }

        return black;
    }

    // ================= COMANDOS ESC/POS =================

    private static byte[] buildEscPosPayload(boolean[][] blackPixels, int width, int height) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        // ESC @ -> inicializa/reinicia a impressora
        buffer.write(0x1B);
        buffer.write(0x40);

        // GS v 0 -> comando de impressão de imagem raster
        int widthBytes = (width + 7) / 8;

        buffer.write(0x1D);
        buffer.write(0x76);
        buffer.write(0x30);
        buffer.write(0x00); // m = 0 (modo normal)
        buffer.write(widthBytes & 0xFF);
        buffer.write((widthBytes >> 8) & 0xFF);
        buffer.write(height & 0xFF);
        buffer.write((height >> 8) & 0xFF);

        for (int y = 0; y < height; y++) {
            for (int byteIndex = 0; byteIndex < widthBytes; byteIndex++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = byteIndex * 8 + bit;
                    boolean isBlack = x < width && blackPixels[y][x];
                    if (isBlack) {
                        b |= (0x80 >> bit);
                    }
                }
                buffer.write(b);
            }
        }

        // Avança papel e corta (se a impressora tiver guilhotina automática)
        buffer.write('\n');
        buffer.write('\n');
        buffer.write('\n');
        buffer.write(0x1D);
        buffer.write(0x56);
        buffer.write(0x00);

        return buffer.toByteArray();
    }
}