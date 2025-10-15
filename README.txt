README 

Contenu
- Serveur.java : lance le serveur multi-clients.
- ClientHandler.java : gère les commandes d’un client (ls, cd, mkdir, delete, upload, download, exit).
- Client.java : client interactif (demande IP/port, envoie les commandes).

Lancer
Terminal A (serveur) :
il faut être dans le directory où il y a les fichiers .jar
  java -jar serveur.jar
  # saisir IP (ENTER=127.0.0.1) et Port (ENTER=5000)

Terminal B (client) :
  java -jar client.jar
  # saisir IP/Port (ou: java -jar client.jar 127.0.0.1 5000)

Commandes côté client
ls
cd <dir> | cd ..
mkdir <dir>
delete <fichier|dossier>
upload <chemin_local_fichier>
download <nom_fichier_serveur>
exit

Emplacements des fichiers
- Serveur : ./storage/
- Client : ./downloads/client-<id>/

