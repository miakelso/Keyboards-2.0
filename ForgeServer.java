import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ForgeServer {

    static Connection conn;
    static boolean usingPostgres;

    public static void main(String[] args) throws Exception {

        // 1️⃣ Connect to Postgres (Render) if DATABASE_URL is set, otherwise SQLite
        conn = connectDatabase();

        Statement stmt = conn.createStatement();

        // 2️⃣ Create admins table if it doesn't exist
        stmt.execute("""
        CREATE TABLE IF NOT EXISTS admins(
            id TEXT PRIMARY KEY,
            "firstName" TEXT,
            "lastName" TEXT,
            email TEXT UNIQUE,
            password TEXT,
            address TEXT,
            role TEXT DEFAULT 'admin'
        )
        """);

        // 3️⃣ Insert default admin
        if (usingPostgres) {
            stmt.execute("""
            INSERT INTO admins(id, "firstName", "lastName", email, password, address, role)
            VALUES('admin-1', 'Forge', 'Admin', 'admin@forge.com', 'Admin123!', 'HQ', 'admin')
            ON CONFLICT (email) DO NOTHING
            """);
        } else {
            stmt.execute("""
            INSERT OR IGNORE INTO admins(id, "firstName", "lastName", email, password, address, role)
            VALUES('admin-1', 'Forge', 'Admin', 'admin@forge.com', 'Admin123!', 'HQ', 'admin')
            """);
        }

        // 3b️⃣ Contact submissions table
        if (usingPostgres) {
            stmt.execute("""
            CREATE TABLE IF NOT EXISTS contact_messages(
                id SERIAL PRIMARY KEY,
                name TEXT,
                email TEXT,
                subject TEXT,
                message TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        } else {
            stmt.execute("""
            CREATE TABLE IF NOT EXISTS contact_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                email TEXT,
                subject TEXT,
                message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """);
        }

        System.out.println("Database ready! using " + (usingPostgres ? "Postgres" : "SQLite") + ".");

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
                String sql = "SELECT id, \"firstName\" AS \"firstName\", \"lastName\" AS \"lastName\", email, password, address, role FROM admins";
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

                String sql = "INSERT INTO admins(id, \"firstName\", \"lastName\", email, password, address, role) VALUES(?, ?, ?, ?, ?, ?, 'admin')";
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
                if (isUniqueViolation(e)) {
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

        // Contact form submission
        server.createContext("/contact", exchange -> {
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

                String name = data.optString("name", "").trim();
                String email = data.optString("email", "").trim().toLowerCase();
                String subject = data.optString("subject", "").trim();
                String message = data.optString("message", "").trim();

                if (name.isEmpty() || email.isEmpty() || subject.isEmpty() || message.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"All fields are required\"}");
                    return;
                }

                String insertSql = "INSERT INTO contact_messages(name, email, subject, message) VALUES(?, ?, ?, ?)";
                PreparedStatement pst = conn.prepareStatement(insertSql);
                pst.setString(1, name);
                pst.setString(2, email);
                pst.setString(3, subject);
                pst.setString(4, message);
                pst.executeUpdate();
                pst.close();

                List<String> adminEmails = getAdminEmails();
                boolean emailed = sendContactEmailToAdmins(adminEmails, name, email, subject, message);

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("saved", true);
                response.put("emailed", emailed);
                if (emailed) {
                    response.put("message", "Message sent to admins successfully");
                } else {
                    response.put("message", "Message saved. Email relay not configured yet.");
                }
                sendResponse(exchange, 200, response.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("ForgeServer running on port " + port);
        System.out.println("Admin endpoints:");
        System.out.println("  GET  /get-admins");
        System.out.println("  POST /add-admin");
        System.out.println("  POST /delete-admin");
        System.out.println("  POST /contact");
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

    static Connection connectDatabase() throws Exception {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            usingPostgres = true;
            String jdbcUrl = toJdbcPostgresUrl(databaseUrl);
            return DriverManager.getConnection(jdbcUrl);
        }
        usingPostgres = false;
        return DriverManager.getConnection("jdbc:sqlite:forge.db");
    }

    static String toJdbcPostgresUrl(String url) {
        if (url.startsWith("jdbc:postgresql://")) {
            return url;
        }
        URI uri = URI.create(url);
        String userInfo = uri.getUserInfo();
        String username = "";
        String password = "";
        if (userInfo != null && userInfo.contains(":")) {
            String[] parts = userInfo.split(":", 2);
            username = parts[0];
            password = parts[1];
        }

        StringBuilder jdbc = new StringBuilder();
        jdbc.append("jdbc:postgresql://")
            .append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbc.append(":").append(uri.getPort());
        }
        jdbc.append(uri.getPath());

        String query = uri.getQuery();
        jdbc.append("?sslmode=require");
        if (query != null && !query.isBlank()) {
            jdbc.append("&").append(query);
        }
        if (!username.isBlank()) {
            jdbc.append("&user=").append(username);
        }
        if (!password.isBlank()) {
            jdbc.append("&password=").append(password);
        }
        return jdbc.toString();
    }

    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state)) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("unique") || m.contains("duplicate");
    }

    static List<String> getAdminEmails() throws SQLException {
        List<String> emails = new ArrayList<>();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT email FROM admins WHERE role = 'admin'");
        while (rs.next()) {
            String email = rs.getString("email");
            if (email != null && !email.isBlank()) {
                emails.add(email);
            }
        }
        rs.close();
        st.close();
        return emails;
    }

    static boolean sendContactEmailToAdmins(List<String> adminEmails, String name, String fromEmail, String subject, String message) {
        String resendApiKey = System.getenv("RESEND_API_KEY");
        String fromAddress = System.getenv("CONTACT_FROM_EMAIL");

        if (resendApiKey == null || resendApiKey.isBlank() || fromAddress == null || fromAddress.isBlank()) {
            return false;
        }
        if (adminEmails == null || adminEmails.isEmpty()) {
            return false;
        }

        try {
            HttpClient client = HttpClient.newHttpClient();
            JSONObject payload = new JSONObject();
            payload.put("from", fromAddress);
            payload.put("to", new JSONArray(adminEmails));
            payload.put("subject", "[Forge Contact] " + subject);

            String html = "<h2>New Contact Form Submission</h2>"
                    + "<p><strong>Name:</strong> " + escapeHtml(name) + "</p>"
                    + "<p><strong>Email:</strong> " + escapeHtml(fromEmail) + "</p>"
                    + "<p><strong>Subject:</strong> " + escapeHtml(subject) + "</p>"
                    + "<p><strong>Message:</strong><br/>" + escapeHtml(message).replace("\n", "<br/>") + "</p>";
            payload.put("html", html);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    static String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}