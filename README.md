# Assistant

Assistant vocal Android personnel, piloté par Claude (API Anthropic), avec le mot d'appel « Hey Claude » détecté hors ligne (Vosk).

## Fonctions

- **Mot d'appel « Hey Claude »** hors ligne (Vosk, modèle français téléchargé au premier lancement)
- **Appels** (« Appelle Julie », traité localement) et **SMS** rédigés par Claude, envoi automatique après 5 s sauf annulation
- **Home Assistant** en lecture seule, limité à une liste blanche de capteurs
- **Gmail** (API officielle) : résumé, recherche, rédaction avec validation manuelle, annonce vocale des nouveaux mails
- **Agenda** du téléphone : lecture et ajout d'événements avec confirmation
- **Alarmes, minuteurs, rappels** (application Horloge)
- **Navigation Google Maps** vers une adresse ou un favori

## Sécurité

- Le contenu des mails et des invitations d'agenda est traité comme une donnée non fiable, jamais comme une instruction.
- Après la lecture d'un mail ou de l'agenda, aucun appel ni SMS n'est possible dans le même échange.
- Aucun mail ni événement n'est créé sans validation à l'écran.
- Home Assistant : lecture seule, uniquement les capteurs de la liste blanche.

## Configuration

Tout ce qui est personnel reste dans `local.properties` (jamais versionné) :

```
CLAUDE_API_KEY=sk-ant-...
HA_URL=https://votre-instance.ui.nabu.casa
HA_TOKEN=jeton-longue-duree-home-assistant
PRENOM=Prénom
VILLE=Ville
FAVORIS=maison=44.0000,1.0000;travail=44.1000,1.1000
```

Liste blanche Home Assistant : copier `app/src/main/assets/home_assistant_capteurs.exemple.json` en `home_assistant_capteurs.json` (ignoré par Git) et y mettre ses propres capteurs.

Gmail : créer un projet Google Cloud, activer l'API Gmail, et un client OAuth Android avec le package `fr.nicopi.assistant` et l'empreinte SHA-1 de sa clé de signature.

## Pile technique

Kotlin, Jetpack Compose, Vosk, reconnaissance vocale Android, API Claude avec outils (tool use).
