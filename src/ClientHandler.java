// ClientHandler.java
import java.net.Socket;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.security.MessageDigest;           // MD5
import java.security.NoSuchAlgorithmException;

public class ClientHandler extends Thread {
    private final Socket socket;
    private final int clientNumber;

    private final Path root = Paths.get("storage").toAbsolutePath().normalize();
    private Path cwd = root;

    public ClientHandler(Socket socket, int clientNumber) {
        this.socket = socket;
        this.clientNumber = clientNumber;
        System.out.println("New connection with client#" + clientNumber + " at " + socket);
        try { Files.createDirectories(root); } catch (IOException ignored) {}
        this.cwd = root;
    }

    @Override public void run() {
        try (DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            out.writeUTF("Hello from server - you are client#" + clientNumber);
            out.writeUTF("CWD: " + relPath());
            out.flush();

            while (true) {
                String op;
                try { op = in.readUTF(); } catch (EOFException e) { break; }

                switch (op) {
                    case "EXIT": { out.writeUTF("Bye!"); out.flush(); Serveur.log(socket, "exit"); return; }
                    case "LS":   { handleLs(out); break; }
                    case "CD":   { String arg = in.readUTF(); handleCd(out, arg); break; }
                    case "MKDIR":{ String name = in.readUTF(); handleMkdir(out, name); break; }
                    case "DELETE":{String name = in.readUTF(); handleDelete(out, name); break; }

                    // ====== UPLOAD avec MD5 : client envoie nom + taille + md5 attendu, puis les octets ======
                    case "UPLOAD": {
                        String remoteName = in.readUTF();
                        long   size       = in.readLong();
                        String clientMd5  = in.readUTF();       // <- MD5 attendu envoyé par le client
                        handleUpload(in, out, remoteName, size, clientMd5);
                        break;
                    }

                    // ====== DOWNLOAD avec MD5 : serveur envoie taille, octets, puis md5 ======
                    case "DOWNLOAD": {
                        String name = in.readUTF();
                        handleDownload(out, name);
                        break;
                    }

                    default: { out.writeUTF("ERR Unknown command"); out.flush(); }
                }
            }
        } catch (IOException e) {
            System.out.println("Error handling client# " + clientNumber + ": " + e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("Connection with client#" + clientNumber + " closed");
        }
    }

    // ---------- COMMANDES de navigation / FS ----------
    private void handleLs(DataOutputStream out) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(cwd)) {
            s.sorted((a,b)->{
                boolean da=Files.isDirectory(a), db=Files.isDirectory(b);
                if (da!=db) return da?-1:1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).forEach(p -> entries.add((Files.isDirectory(p)?"[Folder] ":"[File] ")+p.getFileName().toString()));
        }
        out.writeInt(entries.size());
        for (String line : entries) out.writeUTF(line);
        out.flush();
        Serveur.log(socket, "ls");
    }

    private void handleCd(DataOutputStream out, String arg) throws IOException {
        if (arg==null || arg.isEmpty()) { out.writeUTF("ERR cd: argument manquant"); out.writeUTF("CWD: " + relPath()); out.flush(); return; }
        if ("..".equals(arg)) {
            Path parent = cwd.getParent();
            cwd = (parent!=null && parent.startsWith(root)) ? parent.normalize() : root;
            out.writeUTF("Vous êtes dans le dossier " + relPath() + ".");
            out.writeUTF("CWD: " + relPath());
            out.flush();
            Serveur.log(socket, "cd ..");
            return;
        }
        Path target = cwd.resolve(arg).normalize();
        if (!target.startsWith(root)) out.writeUTF("ERR cd: accès hors racine interdit");
        else if (!Files.exists(target)) out.writeUTF("ERR cd: le dossier n'existe pas");
        else if (!Files.isDirectory(target)) out.writeUTF("ERR cd: ce n'est pas un dossier");
        else { cwd = target; out.writeUTF("Vous êtes dans le dossier " + relPath() + "."); Serveur.log(socket, "cd " + arg); }
        out.writeUTF("CWD: " + relPath());
        out.flush();
    }

    private void handleMkdir(DataOutputStream out, String name) throws IOException {
        if (name==null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            out.writeUTF("ERR mkdir: nom de dossier invalide"); out.flush(); return;
        }
        Path target = cwd.resolve(name).normalize();
        if (!target.startsWith(root)) out.writeUTF("ERR mkdir: accès hors racine interdit");
        else if (Files.exists(target)) out.writeUTF("ERR mkdir: le dossier existe déjà");
        else { Files.createDirectory(target); out.writeUTF("Le dossier " + name + " a été créé."); Serveur.log(socket, "mkdir " + name); }
        out.flush();
    }

    private void handleDelete(DataOutputStream out, String name) throws IOException {
        if (name==null || name.isEmpty()) { out.writeUTF("ERR delete: argument manquant"); out.flush(); return; }
        Path target = cwd.resolve(name).normalize();
        if (!target.startsWith(root)) { out.writeUTF("ERR delete: accès hors racine interdit"); out.flush(); return; }
        if (!Files.exists(target))    { out.writeUTF("ERR delete: introuvable"); out.flush(); return; }
        try { deleteRecursive(target); out.writeUTF("Suppression réussie: " + name); Serveur.log(socket, "delete " + name); }
        catch (IOException e) { out.writeUTF("ERR delete: " + e.getMessage()); }
        out.flush();
    }

    // ---------- TRANSFERTS avec MD5 ----------

    private void handleUpload(DataInputStream in, DataOutputStream out, String remoteName, long size, String clientMd5) throws IOException {
        if (remoteName==null || remoteName.isEmpty()) { out.writeUTF("ERR upload: nom de fichier manquant"); out.flush(); return; }
        if (size < 0) { out.writeUTF("ERR upload: taille négative"); out.flush(); return; }

        remoteName = Paths.get(remoteName).getFileName().toString();
        Path target = cwd.resolve(remoteName).normalize();
        if (!target.startsWith(root)) { out.writeUTF("ERR upload: accès hors racine interdit"); out.flush(); return; }

        MessageDigest md = getMd5();                     // instancie MD5
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            // copie en mettant à jour le MD5 serveur
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
        String serverMd5 = toHex(md.digest());

        if (serverMd5.equalsIgnoreCase(clientMd5)) {
            out.writeUTF("UPLOAD_OK " + remoteName + " size=" + size + " md5=" + serverMd5);
        } else {
            out.writeUTF("UPLOAD_ERR md5_mismatch client=" + clientMd5 + " server=" + serverMd5);
        }
        out.flush();
        Serveur.log(socket, "upload " + remoteName + " size=" + size + " md5=" + serverMd5);
    }

    private void handleDownload(DataOutputStream out, String name) throws IOException {
        if (name==null || name.isEmpty()) { out.writeLong(-1L); out.writeUTF("ERR download: argument manquant"); out.flush(); return; }
        Path src = cwd.resolve(name).normalize();
        if (!src.startsWith(root) || !Files.exists(src) || Files.isDirectory(src)) {
            out.writeLong(-1L); out.writeUTF("ERR download: fichier introuvable"); out.flush(); return;
        }

        long size = Files.size(src);
        out.writeLong(size);                               // 1) envoie la taille

        MessageDigest md = getMd5();                       // on calcule le MD5 pendant l’envoi
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(src))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                out.write(buf, 0, r);                      // 2) envoie les octets
                md.update(buf, 0, r);                      // met à jour le MD5
            }
        }
        String serverMd5 = toHex(md.digest());
        out.writeUTF(serverMd5);                           // 3) envoie le MD5 officiel
        out.flush();

        Serveur.log(socket, "download " + name + " size=" + size + " md5=" + serverMd5);
    }

    // ---------- Utils ----------
    private String relPath() {
        Path rel = root.relativize(cwd);
        String s = rel.toString().replace('\\','/');
        return s.isEmpty() ? "/" : "/" + s;
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            for (Path child : ds) deleteRecursive(child);
        }
        Files.deleteIfExists(p);
    }

    private static MessageDigest getMd5() throws IOException {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("MD5 non supporté", e); }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
