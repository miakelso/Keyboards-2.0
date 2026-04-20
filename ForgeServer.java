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

        // 1️⃣ Connect to the configured Postgres database
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

        // 3c️⃣ Users table for customer accounts
        stmt.execute("""
        CREATE TABLE IF NOT EXISTS users(
            id TEXT PRIMARY KEY,
            "firstName" TEXT,
            "lastName" TEXT,
            email TEXT UNIQUE,
            password TEXT,
            address TEXT,
            role TEXT DEFAULT 'user'
        )
        """);

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

        // User signup
        server.createContext("/signup-user", exchange -> {
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

                String firstName = data.optString("firstName", "").trim();
                String lastName = data.optString("lastName", "").trim();
                String email = data.optString("email", "").trim().toLowerCase();
                String password = data.optString("password", "");
                String address = data.optString("address", "").trim();

                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || password.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing required fields\"}");
                    return;
                }

                String id = "u-" + System.currentTimeMillis();
                String sql = "INSERT INTO users(id, \"firstName\", \"lastName\", email, password, address, role) VALUES(?, ?, ?, ?, ?, ?, 'user')";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, id);
                pst.setString(2, firstName);
                pst.setString(3, lastName);
                pst.setString(4, email);
                pst.setString(5, password);
                pst.setString(6, address);
                pst.executeUpdate();
                pst.close();

                JSONObject user = new JSONObject();
                user.put("id", id);
                user.put("role", "user");
                user.put("firstName", firstName);
                user.put("lastName", lastName);
                user.put("email", email);
                user.put("address", address);

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("user", user);
                sendResponse(exchange, 201, response.toString());
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"An account with that email already exists\"}");
                } else {
                    sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // User login
        server.createContext("/login-user", exchange -> {
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
                String email = data.optString("email", "").trim().toLowerCase();
                String password = data.optString("password", "");

                if (email.isEmpty() || password.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Email and password are required\"}");
                    return;
                }

                String sql = "SELECT id, \"firstName\" AS \"firstName\", \"lastName\" AS \"lastName\", email, address, role FROM users WHERE email = ? AND password = ?";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, email);
                pst.setString(2, password);
                ResultSet rs = pst.executeQuery();

                if (!rs.next()) {
                    rs.close();
                    pst.close();
                    sendResponse(exchange, 401, "{\"success\":false,\"error\":\"Invalid email or password\"}");
                    return;
                }

                JSONObject user = new JSONObject();
                user.put("id", rs.getString("id"));
                user.put("role", rs.getString("role"));
                user.put("firstName", rs.getString("firstName"));
                user.put("lastName", rs.getString("lastName"));
                user.put("email", rs.getString("email"));
                user.put("address", rs.getString("address"));

                rs.close();
                pst.close();

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("user", user);
                sendResponse(exchange, 200, response.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Get user profile by email
        server.createContext("/get-user", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String email = getQueryParam(query, "email").trim().toLowerCase();
                if (email.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Email is required\"}");
                    return;
                }

                String sql = "SELECT id, \"firstName\" AS \"firstName\", \"lastName\" AS \"lastName\", email, address, role FROM users WHERE email = ?";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, email);
                ResultSet rs = pst.executeQuery();
                if (!rs.next()) {
                    rs.close();
                    pst.close();
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"User not found\"}");
                    return;
                }

                JSONObject user = new JSONObject();
                user.put("id", rs.getString("id"));
                user.put("role", rs.getString("role"));
                user.put("firstName", rs.getString("firstName"));
                user.put("lastName", rs.getString("lastName"));
                user.put("email", rs.getString("email"));
                user.put("address", rs.getString("address"));

                rs.close();
                pst.close();

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("user", user);
                sendResponse(exchange, 200, response.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Update user profile
        server.createContext("/update-user", exchange -> {
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

                String oldEmail = data.optString("oldEmail", "").trim().toLowerCase();
                String firstName = data.optString("firstName", "").trim();
                String lastName = data.optString("lastName", "").trim();
                String nextEmail = data.optString("email", "").trim().toLowerCase();
                String password = data.optString("password", "");
                String address = data.optString("address", "").trim();

                if (oldEmail.isEmpty() || nextEmail.isEmpty() || password.isEmpty()) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing required fields\"}");
                    return;
                }

                String sql = "UPDATE users SET \"firstName\" = ?, \"lastName\" = ?, email = ?, password = ?, address = ? WHERE email = ?";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, firstName);
                pst.setString(2, lastName);
                pst.setString(3, nextEmail);
                pst.setString(4, password);
                pst.setString(5, address);
                pst.setString(6, oldEmail);
                int updated = pst.executeUpdate();
                pst.close();

                if (updated == 0) {
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"User not found\"}");
                    return;
                }

                JSONObject user = new JSONObject();
                user.put("firstName", firstName);
                user.put("lastName", lastName);
                user.put("email", nextEmail);
                user.put("address", address);
                user.put("role", "user");

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("user", user);
                sendResponse(exchange, 200, response.toString());
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"That email is already in use\"}");
                } else {
                    sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Get all users (for admin dashboard)
        server.createContext("/get-all-users", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                String sql = "SELECT id, \"firstName\", \"lastName\", email, password, address, role FROM users ORDER BY email";
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql);
                JSONArray users = new JSONArray();
                while (rs.next()) {
                    JSONObject user = new JSONObject();
                    user.put("id", rs.getString("id"));
                    user.put("firstName", rs.getString("firstName"));
                    user.put("lastName", rs.getString("lastName"));
                    user.put("email", rs.getString("email"));
                    user.put("password", rs.getString("password"));
                    user.put("address", rs.getString("address"));
                    user.put("role", rs.getString("role"));
                    users.put(user);
                }
                rs.close();
                st.close();

                sendResponse(exchange, 200, users.toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Delete user
        server.createContext("/delete-user", exchange -> {
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

                String sql = "DELETE FROM users WHERE email = ?";
                PreparedStatement pst = conn.prepareStatement(sql);
                pst.setString(1, email);
                int rows = pst.executeUpdate();
                pst.close();

                JSONObject response = new JSONObject();
                if (rows > 0) {
                    response.put("success", true);
                    response.put("message", "User deleted successfully");
                    sendResponse(exchange, 200, response.toString());
                } else {
                    response.put("success", false);
                    response.put("error", "User not found");
                    sendResponse(exchange, 404, response.toString());
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // Update user (admin version with flexible parameters)
        server.createContext("/admin/update-user", exchange -> {
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
                String firstName = data.optString("firstName", "");
                String lastName = data.optString("lastName", "");
                String newEmail = data.optString("newEmail", "").trim().toLowerCase();
                String newPassword = data.optString("newPassword", "");

                // Get current user first
                String selectSql = "SELECT * FROM users WHERE email = ?";
                PreparedStatement selectPst = conn.prepareStatement(selectSql);
                selectPst.setString(1, email);
                ResultSet rs = selectPst.executeQuery();
                if (!rs.next()) {
                    rs.close();
                    selectPst.close();
                    sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
                    return;
                }
                rs.close();
                selectPst.close();

                // Use provided values or keep existing
                String finalFirstName = !firstName.isEmpty() ? firstName : "?";
                String finalLastName = !lastName.isEmpty() ? lastName : "?";
                String finalEmail = !newEmail.isEmpty() ? newEmail : email;
                String finalPassword = !newPassword.isEmpty() ? newPassword : "?";

                StringBuilder updateSql = new StringBuilder("UPDATE users SET ");
                List<String> updates = new ArrayList<>();
                List<String> values = new ArrayList<>();

                if (!firstName.isEmpty()) {
                    updates.add("\"firstName\" = ?");
                    values.add(firstName);
                }
                if (!lastName.isEmpty()) {
                    updates.add("\"lastName\" = ?");
                    values.add(lastName);
                }
                if (!newEmail.isEmpty()) {
                    updates.add("email = ?");
                    values.add(newEmail);
                }
                if (!newPassword.isEmpty()) {
                    updates.add("password = ?");
                    values.add(newPassword);
                }

                if (updates.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"No fields to update\"}");
                    return;
                }

                updateSql.append(String.join(", ", updates));
                updateSql.append(" WHERE email = ?");
                values.add(email);

                PreparedStatement pst = conn.prepareStatement(updateSql.toString());
                for (int i = 0; i < values.size(); i++) {
                    pst.setString(i + 1, values.get(i));
                }
                int rows = pst.executeUpdate();
                pst.close();

                if (rows > 0) {
                    JSONObject response = new JSONObject();
                    response.put("success", true);
                    response.put("message", "User updated successfully");
                    sendResponse(exchange, 200, response.toString());
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
                }
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    sendResponse(exchange, 400, "{\"error\":\"Email already in use\"}");
                } else {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
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
        System.out.println("  POST /contact");
        System.out.println("  POST /signup-user");
        System.out.println("  POST /login-user");
        System.out.println("  GET  /get-user?email=");
        System.out.println("  POST /update-user");
        System.out.println("  GET  /get-all-users");
        System.out.println("  POST /delete-user");
        System.out.println("  POST /admin/update-user");
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
        usingPostgres = true;
        String internalUrl = "postgresql://mia_kelso_user:O9dAaBZMEYGhDVNZ1z46to7ODqpIAMvT@dpg-d7fusqvlk1mc73f0sop0-a/mia_kelso";
        String externalUrl = "postgresql://mia_kelso_user:O9dAaBZMEYGhDVNZ1z46to7ODqpIAMvT@dpg-d7fusqvlk1mc73f0sop0-a.oregon-postgres.render.com/mia_kelso";
        try {
            return DriverManager.getConnection(toJdbcPostgresUrl(internalUrl));
        } catch (SQLException ignored) {
            return DriverManager.getConnection(toJdbcPostgresUrl(externalUrl));
        }
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

    static String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) return "";
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
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