// Client.java (INTERACTIF)
// - Demande IP & Port au lancement (ENTER = 127.0.0.1:5000)
// - Supporte aussi des arguments : java Client 127.0.0.1 5000
// - Commandes: ls | cd <dir> | mkdir <dir> | delete <f|dir> | upload <pathLocal> | download <fichierServeur> | exit

import java.io.*;
import java.net.Socket;
import java.util.Locale;
import java.util.regex.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Client {

    public static void main(String[] args) {
        try {
            // 1) Récupérer IP/port : args si fournis, sinon prompts interactifs
            String serverIp;
            int serverPort;

            if (args.length >= 2) {
                serverIp = args[0].trim();
                serverPort = Integer.parseInt(args[1].trim());
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                serverIp = askIp(br);
                serverPort = askPort(br);
            }

            // 2) Connexion au serveur
            try (Socket socket = new Socket(serverIp, serverPort);
                 DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 BufferedReader stdin  = new BufferedReader(new InputStreamReader(System.in))) {

                System.out.printf("Connecté au serveur [%s:%d]%n", serverIp, serverPort);

                // 3) Accueil serveur (2 lignes dans notre proto). On tolère l'absence de la 2e si serveur ancien.
                String hello = in.readUTF();
                System.out.println(hello);
                String cwdMsg;
                try { cwdMsg = in.readUTF(); } catch (EOFException e) { cwdMsg = "(CWD non envoyé par le serveur)"; }
                System.out.println(cwdMsg);

                // 4) Préparer un dossier de téléchargements dédié à ce client
                int clientId = extractClientId(hello);
                File downloadsRoot = new File("downloads" + File.separator + "client-" + clientId);
                downloadsRoot.mkdirs();
                System.out.println("Téléchargements → " + downloadsRoot.getPath());

                // 5) Boucle interactive
                System.out.println("Commandes: ls | cd <dir> | mkdir <dir> | delete <f|dir> | upload <pathLocal> | download <fichierServeur> | exit");

                while (true) {
                    System.out.print("> ");
                    String line = stdin.readLine();
                    if (line == null) break;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("\\s+", 2);
                    String cmd = parts[0].toLowerCase(Locale.ROOT);
                    String arg = (parts.length > 1 ? parts[1].trim() : "");

                    switch (cmd) {
                        case "exit": {
                            out.writeUTF("EXIT"); out.flush();
                            // Le serveur renvoie "Bye!"
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

                        // ====== UPLOAD avec MD5 ======
                        case "upload": {
                            if (arg.isEmpty()) { System.out.println("Usage: upload <chemin_local_fichier>"); break; }
                            String path = unquote(arg);
                            File f = new File(path);
                            if (!f.exists() || !f.isFile()) { System.out.println("Fichier local introuvable: " + path); break; }

                            // 1) calcule MD5 local
                            String md5;
                            try { md5 = computeFileMd5(f); }
                            catch (Exception e) { System.out.println("Erreur MD5: " + e.getMessage()); break; }

                            // 2) envoie entête + md5 attendu
                            out.writeUTF("UPLOAD");
                            out.writeUTF(f.getName());
                            out.writeLong(f.length());
                            out.writeUTF(md5);
                            out.flush();

                            // 3) envoie le fichier
                            try (InputStream fis = new BufferedInputStream(new FileInputStream(f))) {
                                copyExactly(fis, out, f.length());
                            }
                            out.flush();

                            // 4) lit ACK (UPLOAD_OK / UPLOAD_ERR)
                            System.out.println(in.readUTF());
                            break;
                        }

                        // ====== DOWNLOAD avec MD5 ======
                        case "download": {
                            if (arg.isEmpty()) { System.out.println("Usage: download <nom_fichier_serveur>"); break; }
                            out.writeUTF("DOWNLOAD"); out.writeUTF(arg); out.flush();

                            long size = in.readLong();
                            if (size < 0) { System.out.println(in.readUTF()); break; } // message d'erreur serveur

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
                                    md.update(buf, 0, r); // calcule MD5 pendant l’écriture
                                    remaining -= r;
                                }
                            }
                            String serverMd5 = in.readUTF();        // md5 officiel envoyé par le serveur
                            String clientMd5 = toHex(md.digest());  // md5 de ce qu’on a écrit
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
            }
        } catch (Exception e) {
            System.err.println("Erreur client: " + e.getMessage());
        }
    }

    // ===== Prompts interactifs =====
    private static String askIp(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Adresse IP du serveur (ENTER=127.0.0.1) : ");
            String ip = br.readLine();
            if (ip == null || ip.trim().isEmpty()) return "127.0.0.1";
            return ip.trim();
        }
    }
    private static int askPort(BufferedReader br) throws IOException {
        while (true) {
            System.out.print("Port du serveur (5000–5050) (ENTER=5000) : ");
            String line = br.readLine();
            if (line == null || line.trim().isEmpty()) return 5000;
            try {
                int p = Integer.parseInt(line.trim());
                if (p >= 5000 && p <= 5050) return p;
            } catch (NumberFormatException ignored) {}
            System.out.println("Port invalide. Choisis un entier entre 5000 et 5050.");
        }
    }

    // ===== Utilitaires =====
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

    private static MessageDigest getMd5() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("MD5");
    }
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
