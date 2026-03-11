import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ForgeServer {

    static Connection conn;

    public static void main(String[] args) throws Exception {

        // 1️⃣ Connect to SQLite and create the database
        conn = DriverManager.getConnection("jdbc:sqlite:forge.db");

        Statement stmt = conn.createStatement();

        // 2️⃣ Create admins table if it doesn't exist
        stmt.execute("""
        CREATE TABLE IF NOT EXISTS admins(
            id TEXT PRIMARY KEY,
            firstName TEXT,
            lastName TEXT,
            email TEXT UNIQUE,
            password TEXT,
            address TEXT,
            role TEXT DEFAULT 'admin'
        )
        """);

        // 3️⃣ Insert default admin
        stmt.execute("""
        INSERT OR IGNORE INTO admins(id, firstName, lastName, email, password, address, role)
        VALUES('admin-1', 'Forge', 'Admin', 'admin@forge.com', 'Admin123!', 'HQ', 'admin')
        """);

        System.out.println("Database ready! forge.db created (if not existed).");

        // 4️⃣ Start HTTP server with API endpoints
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Home page
        server.createContext("/", exchange -> {
            String response = "<html><body><h1>Forge Keyboard Store API</h1>" +
                    "<p>Backend is running!</p></body></html>";
            sendResponse(exchange, 200, response);
        });

        // Get all admins
        server.createContext("/get-admins", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                JSONArray admins = new JSONArray();
                String sql = "SELECT id, firstName, lastName, email, password, address, role FROM admins";
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql);
                while (rs.next()) {
                    JSONObject admin = new JSONObject();
                    admin.put("id", rs.getString("id"));
                    admin.put("firstName", rs.getString("firstName"));
                    admin.put("lastName", rs.getString("lastName"));
                    admin.put("email", rs.getString("email"));
                    admin.put("password", rs.getString("password"));
                    admin.put("address", rs.getString("address"));
                    admin.put("role", rs.getString("role"));
                    admins.put(admin);
                }
                rs.close();
                st.close();
                sendResponse(exchange, 200, admins.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Add a new admin
        server.createContext("/add-admin", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                String body = readBody(exchange);
                JSONObject data = new JSONObject(body);
                
                String id = "admin-" + System.currentTimeMillis();
                String firstName = data.getString("firstName");
                String lastName = data.getString("lastName");
                String email = data.getString("email");
                String password = data.getString("password");
                String address = data.optString("address", "HQ");

                String sql = "INSERT INTO admins(id, firstName, lastName, email, password, address, role) VALUES(?, ?, ?, ?, ?, ?, 'admin')";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, id);
                pst.setString(2, firstName);
                pst.setString(3, lastName);
                pst.setString(4, email);
                pst.setString(5, password);
                pst.setString(6, address);
                pst.executeUpdate();
                pst.close();

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("id", id);
                response.put("message", "Admin added successfully");
                sendResponse(exchange, 201, response.toString());
            } catch (SQLException e) {
                if (e.getMessage().contains("UNIQUE")) {
                    sendResponse(exchange, 400, "{\"error\":\"Email already exists\"}");
                } else {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Delete an admin
        server.createContext("/delete-admin", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                String body = readBody(exchange);
                JSONObject data = new JSONObject(body);
                String email = data.getString("email");

                String sql = "DELETE FROM admins WHERE email = ?";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, email);
                int rows = pst.executeUpdate();
                pst.close();

                JSONObject response = new JSONObject();
                if (rows > 0) {
                    response.put("success", true);
                    response.put("message", "Admin deleted successfully");
                    sendResponse(exchange, 200, response.toString());
                } else {
                    response.put("success", false);
                    response.put("message", "Admin not found");
                    sendResponse(exchange, 404, response.toString());
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("ForgeServer running on port " + port);
        System.out.println("Admin endpoints:");
        System.out.println("  GET  /get-admins");
        System.out.println("  POST /add-admin");
        System.out.println("  POST /delete-admin");
    }

    static void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString();
    }
}