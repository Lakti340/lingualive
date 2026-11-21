# LinguaLive — traduction et sous-titres en temps réel (type Wordly)

Application Android (Kotlin + Jetpack Compose) et petit serveur Node.js.

| Fonction | Comment c'est fait |
|---|---|
| Sous-titres en direct | Reconnaissance vocale Android (`SpeechRecognizer`), relancée en continu |
| Traduction | Google ML Kit **sur chaque téléphone** : chaque participant traduit vers sa propre langue (gratuit, hors-ligne après le 1er téléchargement du modèle) |
| Audio traduit | Synthèse vocale du téléphone (`TextToSpeech`) |
| Transcriptions | Historique complet, en version originale ou traduite, à exporter/partager |
| Résumés | Claude (API Anthropic) via le serveur ; sinon un résumé local automatique |
| **Conversation face à face (2 sens)** | Un téléphone entre deux personnes, écran coupé en deux (moitié haute retournée), un micro chacun |
| **Réunion multilingue (2 sens, N personnes)** | Chacun sur son téléphone : tout le monde peut parler, chacun lit/entend les autres dans sa langue |
| Présentiel / hybride / virtuel / webinaire | Le présentateur obtient un **code à 6 caractères** ; les participants le saisissent, dans la salle ou à distance |
| Vidéo à la demande | Envoi d'un fichier audio/vidéo → transcription (API Whisper compatible) → traduction + résumé |

## 0. Mode 100 % hors-ligne — tout est configuré DANS l'appli

Aucun réglage du téléphone (ni Google, ni voix système). L'écran **📦 Pack hors-ligne** télécharge et gère, pour chaque langue :

| Élément | Moteur intégré à l'appli | Taille |
|---|---|---|
| Traduction | Google ML Kit (sur l'appareil) | ~30 Mo |
| Écoute (voix → texte) | **Vosk** — modèle arabe **tunisien** inclus | 31–158 Mo |
| Voix de lecture (audio traduit) | **Piper** via sherpa-onnx | 30–65 Mo |

- Il suffit d'un passage avec internet pour télécharger les langues, ensuite l'appli n'en a plus besoin.
- Plusieurs téléphones : **le téléphone hôte est le serveur** (son point d'accès Wi-Fi, sans données mobiles) ; les autres le trouvent automatiquement.
- Vidéo à la demande : transcrite sur le téléphone par Vosk (tous les Android).
- Résumé : calculé sur le téléphone.
- Japonais, coréen, hindi : pas de voix Piper → la voix du téléphone sert de secours.
- Si une langue n'est pas installée dans l'appli, elle se rabat sur les moteurs du téléphone.

Le serveur Node (`server/`) n'est utile qu'en mode en ligne (résumés IA par Claude, Whisper).

## 1. Obtenir l'APK

**Option A, sans rien installer (GitHub Actions)**
1. Crée un dépôt GitHub et pousse ce dossier.
2. Onglet **Actions** → workflow *Build APK* (il démarre tout seul à chaque push).
3. À la fin du build, télécharge l'artefact **LinguaLive-apk** → `app-debug.apk`.
4. Copie-le sur ton téléphone et installe-le (autorise les « sources inconnues »).

**Option B, Android Studio**
Ouvre le dossier `android/`, attends la synchronisation Gradle (elle télécharge aussi la bibliothèque sherpa-onnx au premier lancement), puis *Build → Build APK(s)*.

## 2. Lancer le serveur

```bash
cd server
npm install
cp .env.example .env      # renseigne tes clés (facultatif)
export $(cat .env | xargs) && npm start
```
Ou avec Docker : `docker build -t lingualive server && docker run -p 8080:8080 --env-file server/.env lingualive`

Dans l'appli (écran d'accueil), renseigne l'adresse du serveur :
- même Wi-Fi : `ws://192.168.x.x:8080/ws` (l'IP de ton PC)
- en ligne (Render, Railway, Fly.io, VPS…) : `wss://ton-domaine/ws`

Sans serveur, le mode **Présenter** fonctionne quand même seul : sous-titres et traduction sur un seul téléphone.

## 3. Utilisation
1. Le présentateur : **Présenter** → choisit sa langue → *Démarrer* → partage le code.
2. Les participants : **Rejoindre** → code + leur langue → sous-titres ; activer *Audio traduit* pour écouter (avec des écouteurs).
3. Pour discuter dans les deux sens : **Conversation face à face** (un téléphone) ou **Réunion multilingue** (un téléphone chacun ; mettez des écouteurs si l'audio traduit est activé, sinon le micro peut capter la voix de synthèse).
4. À tout moment : **Transcription & résumé** → *Générer le résumé* / *Partager*.

Pour un webinaire Zoom/Teams : pose le téléphone du présentateur près du haut-parleur (ou utilise un câble audio), puis partage le code dans le chat.

## Limites actuelles et pistes
- La reconnaissance vocale Android dépend du moteur Google installé ; la qualité varie selon les langues (l'arabe tunisien, par exemple, est moins bien reconnu que l'arabe standard).
- La vidéo à la demande est limitée à 25 Mo (limite de l'API Whisper) ; au-delà, il faut découper le fichier côté serveur (ffmpeg).
- Pour du professionnel : remplacer `SpeechRecognizer` par un STT en streaming (Deepgram, Google Cloud Speech, Azure) côté serveur, ajouter une page web pour les participants sans l'appli, des comptes et une clé de signature pour le Play Store.
