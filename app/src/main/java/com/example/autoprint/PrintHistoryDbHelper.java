package com.example.autoprint;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Guarda um histórico de cada fotografia impressa: quando foi tirada, onde está
 * guardada, e qual o template usado nessa impressão.
 */
public class PrintHistoryDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "print_history.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_NAME = "print_history";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_TIMESTAMP = "timestamp";     // epoch millis
    public static final String COLUMN_PHOTO_PATH = "photo_path";   // Uri/caminho da foto
    public static final String COLUMN_TEMPLATE_PATH = "template_path"; // caminho do template, ou etiqueta do fixo

    public PrintHistoryDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_TIMESTAMP + " INTEGER NOT NULL, " +
                COLUMN_PHOTO_PATH + " TEXT, " +
                COLUMN_TEMPLATE_PATH + " TEXT" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    // Regista uma nova entrada no histórico. Deve ser chamado fora da thread principal.
    public long insertRecord(long timestamp, String photoPath, String templatePath) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TIMESTAMP, timestamp);
        values.put(COLUMN_PHOTO_PATH, photoPath);
        values.put(COLUMN_TEMPLATE_PATH, templatePath);
        return db.insert(TABLE_NAME, null, values);
    }

    // Devolve todo o histórico, do mais recente para o mais antigo
    public Cursor getAllRecords() {
        SQLiteDatabase db = getReadableDatabase();
        return db.query(TABLE_NAME, null, null, null, null, null, COLUMN_TIMESTAMP + " DESC");
    }
}