// Serveur.java
import java.net.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Serveur {
    private static ServerSocket listener;

    // Port du TP : 5000–5050
    private static boolean isValidPort(int p) { return p >= 5000 && p <= 5050; }

    public static void main(String[] args) {
        try {
            // 1) Demande IP/port à l'utilisateur (avec valeurs par défaut pour aller plus vite)
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            String ip = askIp(br);
            int port  = askPort(br);

            // 2) Bind du serveur
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            try {
                InetAddress addr = InetAddress.getByName(ip);
                listener.bind(new InetSocketAddress(addr, port));
            } catch (BindException e) {
                System.err.println("Port " + port + " déjà utilisé sur " + ip + ". Relancez et choisissez un autre port.");
                return;
            } catch (UnknownHostException e) {
                System.err.println("Adresse IP invalide: " + ip);
                return;
            }

            System.out.printf("The server is running on %s:%d%n", ip, port);

            // 3) Fermeture avec Ctrl+C (pas tres necesaire)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (listener != null && !listener.isClosed()) listener.close(); } catch (Exception ignored) {}
            }));
            
            // Boucle d’acceptation : le serveur crée et DÉMARRE un nouveau thread par client.
            // La logique exécutée en parallèle est dans ClientHandler.run().
            
            int clientNumber = 0;
            try {
                while (true) {
                    Socket s = listener.accept();                 // bloquant
                    new ClientHandler(s, clientNumber++).start(); // un thread par client
                }
            } finally {
                listener.close();
            }
        } catch (IOException e) {
            System.err.println("Erreur I/O serveur: " + e.getMessage());
        }
    }

    // === Prompts interactifs === 
    //Pour gérer les bons formats pour les adresses IP et les numéros de ports 

    private static String askIp(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Adresse IP d’écoute (ENTER = 127.0.0.1) : ");
            String line = br.readLine();
            if (line == null) return "127.0.0.1"; // fallback si stdin fermé
            line = line.trim();
            if (line.isEmpty()) return "127.0.0.1";
            // Laisse InetAddress valider (permet aussi un hostname si jamais)
            try {
                InetAddress.getByName(line);
                return line;
            } catch (UnknownHostException e) {
                System.out.println("Adresse invalide. Exemples: 127.0.0.1, 0.0.0.0, 192.168.1.20");
            }
        }
    }

    private static int askPort(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Port (5000–5050) (ENTER = 5000) : ");
            String line = br.readLine();
            if (line == null || line.trim().isEmpty()) return 5000;
            try {
                int p = Integer.parseInt(line.trim());
                if (isValidPort(p)) return p;
            } catch (NumberFormatException ignored) {}
            System.out.println("Port invalide. Choisis un entier entre 5000 et 5050.");
        }
    }

    // === Logging demandé par l'énoncé ===
    //Affichages des commandes de chaque client à chaque fois avec la date, numéro de port etc.
    public static void log(Socket s, String cmd) {
        InetSocketAddress rsa = (InetSocketAddress) s.getRemoteSocketAddress();
        String ip   = rsa.getAddress().getHostAddress();
        int    port = rsa.getPort();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd@HH:mm:ss"));
        System.out.printf("[%s:%d - %s] : %s%n", ip, port, ts, cmd);
    }
}


