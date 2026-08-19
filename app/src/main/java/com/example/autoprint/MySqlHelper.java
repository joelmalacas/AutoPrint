package com.example.autoprint;

import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Liga diretamente a uma base de dados MySQL na rede (sem passar por nenhuma API
 * intermédia) e executa comandos SQL. Todos os métodos fazem I/O de rede, por isso
 * têm de ser chamados FORA da thread principal (dentro de uma Thread própria).
 */
public class MySqlHelper {

    private static final String TAG = "AutoPrintDebug";

    // ====== DB DATA ======
    private static final String DB_HOST = "malacas.pt";
    private static final int DB_PORT = 3306;
    private static final String DB_NAME = "AutoPrint";
    private static final String DB_USER = "AutoPrint";
    private static final String DB_PASSWORD = "AutoPrint2026#!";
    // ==========================================

    private static final String JDBC_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Driver MySQL não encontrado — confirma a dependência no build.gradle", e);
        }
    }

    /**
     * Regista uma impressão na base de dados remota (espelha o que já fica guardado
     * localmente no PrintHistoryDbHelper). Devolve false em caso de falha, sem lançar
     * exceção — uma falha na base de dados remota nunca deve impedir a app de funcionar.
     *
     * A tabela remota correspondente pode ser criada assim:
     *   CREATE TABLE print_history (
     *       id INT AUTO_INCREMENT PRIMARY KEY,
     *       timestamp BIGINT NOT NULL,
     *       photo_path TEXT,
     *       template_path TEXT
     *   );
     */
    public static boolean insertPrintRecord(long timestamp, String photoPath, String templatePath) {
        String sql = "INSERT INTO print_history (timestamp, photo_path, template_path) VALUES (?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, timestamp);
            stmt.setString(2, photoPath);
            stmt.setString(3, templatePath);
            stmt.executeUpdate();
            return true;

        } catch (Throwable e) {
            // Throwable (não só SQLException) porque um problema no driver JDBC pode
            // lançar erros como NoClassDefFoundError, que nunca devem crashar a app
            Log.e(TAG, "Erro ao gravar na base de dados remota", e);
            return false;
        }
    }

    /**
     * Executa um comando SQL genérico (INSERT/UPDATE/DELETE) com parâmetros, para
     * qualquer outra operação além do histórico de impressões.
     *
     * Exemplo de uso:
     *   MySqlHelper.executeUpdate("UPDATE print_history SET template_path = ? WHERE id = ?",
     *           "novo_template.jpg", 5);
     */
    public static boolean executeUpdate(String sql, Object... params) {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
            return true;

        } catch (Throwable e) {
            Log.e(TAG, "Erro ao executar comando na base de dados remota", e);
            return false;
        }
    }
}