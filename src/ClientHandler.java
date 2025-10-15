// ClientHandler.java — gère 1 client (dans un thread) : commandes + transferts MD5
import java.net.Socket;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.security.MessageDigest;           // Calcul de hash MD5
import java.security.NoSuchAlgorithmException;

public class ClientHandler extends Thread {
    // === État spécifique à CE client ===
    private final Socket socket;             // socket connecté à ce client
    private final int clientNumber;          // identifiant logique (0,1,2,...) donné par le serveur

    // Racine de stockage du serveur (sandbox). On travaille TOUJOURS sous "storage/"
    private final Path root = Paths.get("storage").toAbsolutePath().normalize();
    // Répertoire courant de CE client (modifiable par 'cd')
    private Path cwd = root;

    public ClientHandler(Socket socket, int clientNumber) {
        this.socket = socket;
        this.clientNumber = clientNumber;
        System.out.println("New connection with client#" + clientNumber + " at " + socket);
        try { Files.createDirectories(root); } catch (IOException ignored) {} // crée storage/ si absent
        this.cwd = root;                          // point de départ : la racine
    }

    @Override public void run() {
        // Flux binaires "structurés" : UTF pour textes/commandes, octets pour fichiers
        try (DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // Petit "handshake" : message d'accueil + chemin courant
            out.writeUTF("Hello from server - you are client#" + clientNumber);
            out.writeUTF("CWD: " + relPath());
            out.flush();

            // Boucle principale : on lit une commande, on la traite
            while (true) {
                String op;
                try { op = in.readUTF(); }           // lit la commande (EXIT/LS/CD/...)
                catch (EOFException e) { break; }    // client a fermé brutalement → on sort

                switch (op) {
                    case "EXIT": { // fin de session
                        out.writeUTF("Bye!"); out.flush();
                        Serveur.log(socket, "exit");
                        return;                     // termine le thread
                    }
                    case "LS":   { handleLs(out); break; } // liste le dossier courant
                    case "CD":   { String arg = in.readUTF(); handleCd(out, arg); break; } // change de dossier
                    case "MKDIR":{ String name = in.readUTF(); handleMkdir(out, name); break; } // crée un dossier
                    case "DELETE":{String name = in.readUTF(); handleDelete(out, name); break; } // supprime fichier/dossier

                    // ====== UPLOAD : le client envoie nom + taille + md5, puis les octets ======
                    case "UPLOAD": {
                        String remoteName = in.readUTF();
                        long   size       = in.readLong();
                        String clientMd5  = in.readUTF(); // MD5 calculé côté client
                        handleUpload(in, out, remoteName, size, clientMd5);
                        break;
                    }

                    // ====== DOWNLOAD : on envoie taille, octets, puis md5 ======
                    case "DOWNLOAD": {
                        String name = in.readUTF();
                        handleDownload(out, name);
                        break;
                    }

                    default: { // commande inconnue
                        out.writeUTF("ERR Unknown command");
                        out.flush();
                    }
                }
            }
        } catch (IOException e) {
            // Erreur de communication avec ce client (socket cassée, etc.)
            System.out.println("Error handling client# " + clientNumber + ": " + e);
        } finally {
            // Fermeture sécurité
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("Connection with client#" + clientNumber + " closed");
        }
    }

    // ---------- COMMANDES de navigation / système de fichiers ----------

    // LS : renvoie d'abord le nombre d'entrées, puis chaque ligne "[$type] nom"
    private void handleLs(DataOutputStream out) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(cwd)) {
            // Tri : dossiers en premier, puis ordre alphabétique insensible à la casse
            s.sorted((a,b)->{
                boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
                if (da != db) return da ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).forEach(p -> entries.add((Files.isDirectory(p) ? "[Folder] " : "[File] ")
                                        + p.getFileName().toString()));
        }
        out.writeInt(entries.size());      // nb d'entrées
        for (String line : entries) out.writeUTF(line);
        out.flush();
        Serveur.log(socket, "ls");
    }

    // CD : gère 'cd ..' et empêche toute sortie de la racine "storage/"
    private void handleCd(DataOutputStream out, String arg) throws IOException {
        if (arg == null || arg.isEmpty()) {
            out.writeUTF("ERR cd: argument manquant");
            out.writeUTF("CWD: " + relPath());
            out.flush();
            return;
        }
        if ("..".equals(arg)) {                    // remonter d'un cran
            Path parent = cwd.getParent();
            // si parent est nul ou sort de la racine → on reste à root
            cwd = (parent != null && parent.startsWith(root)) ? parent.normalize() : root;
            out.writeUTF("Vous êtes dans le dossier " + relPath() + ".");
            out.writeUTF("CWD: " + relPath());
            out.flush();
            Serveur.log(socket, "cd ..");
            return;
        }
        // cd vers un enfant : normalise et vérifie qu'on reste sous root
        Path target = cwd.resolve(arg).normalize();
        if (!target.startsWith(root))            out.writeUTF("ERR cd: accès hors racine interdit");
        else if (!Files.exists(target))          out.writeUTF("ERR cd: le dossier n'existe pas");
        else if (!Files.isDirectory(target))     out.writeUTF("ERR cd: ce n'est pas un dossier");
        else { cwd = target; out.writeUTF("Vous êtes dans le dossier " + relPath() + "."); Serveur.log(socket, "cd " + arg); }
        out.writeUTF("CWD: " + relPath());
        out.flush();
    }

    // MKDIR : crée un dossier (nom simple, pas de séparateurs)
    private void handleMkdir(DataOutputStream out, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.contains("/") || name.contains("\\")) {
            out.writeUTF("ERR mkdir: nom de dossier invalide");
            out.flush();
            return;
        }
        Path target = cwd.resolve(name).normalize();
        if (!target.startsWith(root))        out.writeUTF("ERR mkdir: accès hors racine interdit");
        else if (Files.exists(target))       out.writeUTF("ERR mkdir: le dossier existe déjà");
        else { Files.createDirectory(target); out.writeUTF("Le dossier " + name + " a été créé."); Serveur.log(socket, "mkdir " + name); }
        out.flush();
    }

    // DELETE : supprime fichier OU dossier (récursif)
    private void handleDelete(DataOutputStream out, String name) throws IOException {
        if (name == null || name.isEmpty()) { out.writeUTF("ERR delete: argument manquant"); out.flush(); return; }
        Path target = cwd.resolve(name).normalize();
        if (!target.startsWith(root)) { out.writeUTF("ERR delete: accès hors racine interdit"); out.flush(); return; }
        if (!Files.exists(target))    { out.writeUTF("ERR delete: introuvable");               out.flush(); return; }
        try {
            deleteRecursive(target);
            out.writeUTF("Suppression réussie: " + name);
            Serveur.log(socket, "delete " + name);
        } catch (IOException e) {
            out.writeUTF("ERR delete: " + e.getMessage());
        }
        out.flush();
    }

    // ---------- TRANSFERTS de fichiers avec vérification MD5 ----------

    // UPLOAD : reçoit un fichier du client, calcule le MD5 serveur, compare au MD5 client
    private void handleUpload(DataInputStream in, DataOutputStream out, String remoteName, long size, String clientMd5) throws IOException {
        if (remoteName == null || remoteName.isEmpty()) { out.writeUTF("ERR upload: nom de fichier manquant"); out.flush(); return; }
        if (size < 0) { out.writeUTF("ERR upload: taille négative"); out.flush(); return; }

        // Sécurise le nom (basename) puis construit la cible sous cwd
        remoteName = Paths.get(remoteName).getFileName().toString();
        Path target = cwd.resolve(remoteName).normalize();
        if (!target.startsWith(root)) { out.writeUTF("ERR upload: accès hors racine interdit"); out.flush(); return; }

        // Prépare le calcul MD5 pendant l'écriture sur disque
        MessageDigest md = getMd5();
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] buf = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int r = in.read(buf, 0, (int)Math.min(buf.length, remaining)); // lit jusqu'à 'remaining'
                if (r == -1) throw new EOFException("Flux terminé avant d'avoir reçu tous les octets");
                fos.write(buf, 0, r);        // écrit sur disque
                md.update(buf, 0, r);        // met à jour le MD5 serveur
                remaining -= r;
            }
        }
        String serverMd5 = toHex(md.digest());   // MD5 final côté serveur

        // Compare avec le MD5 fourni par le client et répond
        if (serverMd5.equalsIgnoreCase(clientMd5)) {
            out.writeUTF("UPLOAD_OK " + remoteName + " size=" + size + " md5=" + serverMd5);
        } else {
            out.writeUTF("UPLOAD_ERR md5_mismatch client=" + clientMd5 + " server=" + serverMd5);
        }
        out.flush();
        Serveur.log(socket, "upload " + remoteName + " size=" + size + " md5=" + serverMd5);
    }

    // DOWNLOAD : envoie la taille, les octets du fichier, puis le MD5 calculé côté serveur
    private void handleDownload(DataOutputStream out, String name) throws IOException {
        if (name == null || name.isEmpty()) { out.writeLong(-1L); out.writeUTF("ERR download: argument manquant"); out.flush(); return; }
        Path src = cwd.resolve(name).normalize();
        // Vérifie l'existence et interdit les dossiers
        if (!src.startsWith(root) || !Files.exists(src) || Files.isDirectory(src)) {
            out.writeLong(-1L); out.writeUTF("ERR download: fichier introuvable"); out.flush(); return;
        }

        long size = Files.size(src);
        out.writeLong(size);                         // (1) annonce la taille à recevoir côté client

        MessageDigest md = getMd5();                 // on calcule le MD5 pendant l'envoi
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(src))) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                out.write(buf, 0, r);               // (2) envoie les octets
                md.update(buf, 0, r);               // met à jour le MD5
            }
        }
        String serverMd5 = toHex(md.digest());
        out.writeUTF(serverMd5);                    // (3) envoie le MD5 officiel
        out.flush();

        Serveur.log(socket, "download " + name + " size=" + size + " md5=" + serverMd5);
    }

    // ---------- Utilitaires ----------

    // Affiche un chemin "propre" relatif à la racine ("/", "/docs", ...)
    private String relPath() {
        Path rel = root.relativize(cwd);
        String s = rel.toString().replace('\\','/');
        return s.isEmpty() ? "/" : "/" + s;
    }

    // Suppression récursive (fichier ou dossier)
    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path child : ds) deleteRecursive(child);
            }
        }
        Files.deleteIfExists(p);
    }

    // Obtenir un MessageDigest MD5 (emballé en IOException pour simplifier l'appelant)
    private static MessageDigest getMd5() throws IOException {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("MD5 non supporté", e); }
    }

    // Conversion d'un tableau d'octets en hexadécimal (minuscule)
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
