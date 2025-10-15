// Client.java — client interactif avec :
// - Arguments OU prompts pour IP/port (avec validation du bon format dentree)
// - Commandes: ls, cd, mkdir, delete, upload, download, exit
// - Vérification d'intégrité MD5 à l'upload et au download (nous ne savons pas si c'Est demandé ou non mais on l'a fait)

import java.io.*;
import java.net.Socket;
import java.util.Locale;
import java.util.regex.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Client {

    public static void main(String[] args) {
        String serverIp = null;
        Integer serverPort = null;

        // 1) MODE ARGUMENTS si 2 args fournis et valides ; sinon on bascule en MODE PROMPTS (entrées utilisateurs0)
        if (args.length >= 2) {
            String ipArg = args[0].trim();
            Integer pArg = tryParseInt(args[1].trim());
            if ((isValidIPv4(ipArg) || "localhost".equalsIgnoreCase(ipArg)) && pArg != null && isValidPort(pArg)) {
                serverIp = ipArg; //on garde les arguments fournis
                serverPort = pArg;
            } else {
                System.out.println("Arguments invalides (IP/port). Passage en mode interactif.");
            }
        }

        // 2) MODE PROMPTS 
        if (serverIp == null || serverPort == null) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                serverIp   = askIp(br);     // ENTER = 127.0.0.1
                serverPort = askPort(br);   // ENTER = 5000
            } catch (IOException e) {
                System.err.println("Erreur lecture console: " + e.getMessage());
                return;
            }
        }

        // 3) Connexion & boucle interactive, sil y a un echec, IOException est affiche et sortie
        try (Socket socket = new Socket(serverIp, serverPort);
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             BufferedReader stdin  = new BufferedReader(new InputStreamReader(System.in))) {

            System.out.printf("Connecté au serveur [%s:%d]%n", serverIp, serverPort);

            // Accueil (tolère les serveurs qui n'envoient qu'une ligne)
            String hello = in.readUTF();
            System.out.println(hello);
            String cwdMsg;
            try { cwdMsg = in.readUTF(); } catch (EOFException e) { cwdMsg = "(CWD non envoyé par le serveur)"; }
            System.out.println(cwdMsg);

            // Dossier de téléchargements dédié pour gerer les downloads de chaque client
            int clientId = extractClientId(hello);
            File downloadsRoot = new File("downloads" + File.separator + "client-" + clientId);
            downloadsRoot.mkdirs();
            System.out.println("Téléchargements → " + downloadsRoot.getPath());
            System.out.println("Commandes: ls | cd <dir> | mkdir <dir> | delete <f|dir> | upload <pathLocal> | download <fichier> | exit");

            //boucle itérative
            while (true) {
                System.out.print("> ");
                String line = stdin.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toLowerCase(Locale.ROOT);
                String arg = (parts.length > 1 ? parts[1].trim() : "");

                // les differentes commandes possibles
                switch (cmd) {
                    case "exit": {
                        out.writeUTF("EXIT"); out.flush();
                        try { System.out.println(in.readUTF()); } catch (EOFException ignored) {}
                        return;
                    }
                    case "ls": {
                        out.writeUTF("LS"); out.flush();
                        int n = in.readInt();
                        if (n == 0) System.out.println("(vide)");
                        for (int i = 0; i < n; i++) System.out.println(in.readUTF());
                        break;
                    }
                    case "cd": {
                        if (arg.isEmpty()) { System.out.println("Usage: cd <dir|..>"); break; }
                        out.writeUTF("CD"); out.writeUTF(arg); out.flush();
                        System.out.println(in.readUTF()); // message (succès/erreur)
                        System.out.println(in.readUTF()); // CWD
                        break;
                    }
                    case "mkdir": {
                        if (arg.isEmpty()) { System.out.println("Usage: mkdir <dir>"); break; }
                        out.writeUTF("MKDIR"); out.writeUTF(arg); out.flush();
                        System.out.println(in.readUTF());
                        break;
                    }
                    case "delete": {
                        if (arg.isEmpty()) { System.out.println("Usage: delete <fichier|dossier>"); break; }
                        out.writeUTF("DELETE"); out.writeUTF(arg); out.flush();
                        System.out.println(in.readUTF());
                        break;
                    }

                    // ===== UPLOAD (avec MD5) =====
                    case "upload": { //ici on met une en-tête avec le nom, la taille, et le md5 puis octets
                        if (arg.isEmpty()) { System.out.println("Usage: upload <chemin_local_fichier>"); break; }
                        String path = unquote(arg);
                        File f = new File(path);
                        if (!f.exists() || !f.isFile()) { System.out.println("Fichier local introuvable: " + path); break; }

                        String md5;
                        try { md5 = computeFileMd5(f); }
                        catch (Exception e) { System.out.println("Erreur MD5: " + e.getMessage()); break; }

                        out.writeUTF("UPLOAD");
                        out.writeUTF(f.getName());
                        out.writeLong(f.length());
                        out.writeUTF(md5);
                        out.flush();

                        try (InputStream fis = new BufferedInputStream(new FileInputStream(f))) {
                            copyExactly(fis, out, f.length());
                        }
                        out.flush();

                        System.out.println(in.readUTF()); // UPLOAD_OK / UPLOAD_ERR .. etc. 
                        break;
                    }

                    // ===== DOWNLOAD (avec MD5) =====
                    case "download": { //pareil que upload 
                        if (arg.isEmpty()) { System.out.println("Usage: download <nom_fichier_serveur>"); break; }
                        out.writeUTF("DOWNLOAD"); out.writeUTF(arg); out.flush();

                        long size = in.readLong();
                        if (size < 0) { System.out.println(in.readUTF()); break; }

                        File outFile = new File(downloadsRoot, arg);
                        File parent = outFile.getParentFile(); if (parent != null) parent.mkdirs();

                        MessageDigest md = getMd5();
                        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                            byte[] buf = new byte[8192];
                            long remaining = size;
                            while (remaining > 0) {
                                int r = in.read(buf, 0, (int)Math.min(buf.length, remaining));
                                if (r == -1) throw new EOFException("Flux terminé avant d'avoir reçu tous les octets");
                                fos.write(buf, 0, r);
                                md.update(buf, 0, r);
                                remaining -= r;
                            }
                        }
                        //On print les deux md5 pour montrer si c'Est correct ou non 
                        String serverMd5 = in.readUTF();
                        String clientMd5 = toHex(md.digest());
                        String status = clientMd5.equalsIgnoreCase(serverMd5) ? "OK" : "MD5 MISMATCH";
                        System.out.println("Download " + status + " : " + arg + " (" + size + " octets)");
                        System.out.println("  server md5 = " + serverMd5);
                        System.out.println("  client md5 = " + clientMd5);
                        System.out.println("→ sauvegardé dans " + outFile.getPath());
                        break;
                    }

                    default:
                        System.out.println("Commande inconnue. Essayez: ls, cd, mkdir, delete, upload, download, exit");
                }
            }
        } catch (Exception e) {
            //si la connexion echoue ou autre I/O, on affiche et on sort
            System.err.println("Erreur client: " + e.getMessage());
        }
    }

    // ===== Validation IP/Port & prompts ===== pareil que dans le serveur
    private static boolean isValidIPv4(String ip) {
        if (ip == null) return false;
        if ("localhost".equalsIgnoreCase(ip)) return true;
        String ipv4 = "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$";
        return ip.matches(ipv4);
    }
    private static boolean isValidPort(int p) { return p >= 5000 && p <= 5050; }
    private static String askIp(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Adresse IP du serveur (ENTER=127.0.0.1) : ");
            String ip = br.readLine();
            if (ip == null || ip.trim().isEmpty()) return "127.0.0.1";
            ip = ip.trim();
            if (isValidIPv4(ip) || "localhost".equalsIgnoreCase(ip)) return ip;
            System.out.println("IP invalide. Exemple: 127.0.0.1 (ou 'localhost').");
        }
    }
    private static int askPort(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Port du serveur (5000–5050) (ENTER=5000) : ");
            String line = br.readLine();
            if (line == null || line.trim().isEmpty()) return 5000;
            Integer p = tryParseInt(line.trim());
            if (p != null && isValidPort(p)) return p;
            System.out.println("Port invalide. Choisis un entier entre 5000 et 5050.");
        }
    }
    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    // ===== Utilitaires divers =====
    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buf, 0, (int)Math.min(buf.length, remaining));
            if (read == -1) throw new EOFException("Flux terminé avant d'avoir reçu tous les octets");
            out.write(buf, 0, read);
            remaining -= read;
        }
    }
    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("'")  && s.endsWith("'"))  ||
            (s.startsWith("“")  && s.endsWith("”"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
    private static int extractClientId(String hello) {
        Matcher m = Pattern.compile("client#(\\d+)").matcher(hello);
        if (m.find()) return Integer.parseInt(m.group(1));
        return (int)(System.currentTimeMillis() % 100000);
    }
    private static MessageDigest getMd5() throws NoSuchAlgorithmException { return MessageDigest.getInstance("MD5"); }
    private static String computeFileMd5(File f) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = getMd5();
        try (InputStream is = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
        }
        return toHex(md.digest());
    }
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

