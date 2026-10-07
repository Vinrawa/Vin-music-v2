 VIN Music

A modern Android music player focused on intelligent discovery, offline playback, lyrics, personalization, and a polished native experience.

VIN Music is a feature-rich Android music player built around the idea that a music player should do more than simply play songs. It combines a modern UI, personalized recommendations, synchronized lyrics, offline playback, audio processing, and intelligent music discovery into one application.

---

 Features

 Music Playback

- High-quality audio playback
- Background playback
- Persistent playback state
- Queue management
- Seek and playback controls
- Fast stream resolution
- Local/offline music playback

 Intelligent Recommendations

VIN Music includes a personalized recommendation system designed around the listener's actual music taste.

TasteDNA builds a representation of the user's listening preferences and compares tracks using music metadata and similarity calculations.

The recommendation pipeline can use:

- Genre
- Artist
- Tags
- Listening history
- Track similarity
- User interactions

The system is designed to progressively understand the listener instead of relying entirely on generic popular-music recommendations.

 Smart Radio

Smart Radio generates continuous music recommendations based on the currently playing track and the listener's taste profile.

Instead of manually selecting every next song, users can start a radio session and let the recommendation system build the queue.

 Lyrics

- Synchronized lyrics
- Automatic lyrics lookup
- Track-based lyric matching
- Local lyric support
- Playback-position synchronization

VIN Music integrates lyric services to provide lyrics without requiring users to manually search for every song.

 Offline Music

VIN Music supports offline playback for downloaded tracks.

The application is designed to keep playback independent from network availability once music has been downloaded.

Planned/expanded local-library support includes automatic discovery of music stored in:

- Music
- Downloads
- WhatsApp
- Recordings
- Other device media folders

🎨 Modern UI

VIN Music focuses heavily on visual polish and interaction quality.

The interface includes:

- Dark-first design
- Glass/surface effects
- Material-inspired components
- Dynamic playback UI
- Smooth transitions
- Album artwork
- Responsive layouts
- Marquee handling for long artist/title names

The goal is to make the application feel closer to a modern commercial music product rather than a basic Android media player.

---

 TasteDNA

One of the core experimental systems inside VIN Music is TasteDNA.

The system converts a user's music preferences into a numerical representation and uses similarity calculations to discover tracks that are likely to match their taste.

A simplified representation looks like:

User Listening History
        ↓
Metadata Extraction
        ↓
TasteDNA Profile
        ↓
Track Feature Vector
        ↓
Similarity Calculation
        ↓
Personalized Recommendations

VIN Music uses vector-based similarity calculations to compare tracks and user preferences.

The recommendation database was built from a large music dataset and optimized for mobile usage.

---

 Architecture

VIN Music follows a modular Android architecture with separate responsibilities for:

UI Layer
   ↓
ViewModel / State
   ↓
Domain / Recommendation Logic
   ↓
Repository Layer
   ↓
Local Database / Network / Media Services

Major components include:

- Android UI
- Playback engine
- Local database
- Recommendation engine
- Lyrics pipeline
- Network services
- Download manager
- Media/session integration
- Image caching
- Analytics and crash monitoring

Performance-sensitive operations are moved away from the Android main thread to prevent UI freezes and ANRs.

---

 Performance

Performance has been a major part of VIN Music's development.

The application has been tested and optimized around problems such as:

- UI-thread blocking
- Slow stream resolution
- Large image processing
- Database operations
- Download performance
- Network failures
- Android ANRs

Heavy operations such as database access, file operations, image processing, and stream resolution are handled asynchronously where appropriate.

VIN Music also uses a persistent streaming approach rather than repeatedly creating small network downloads, reducing unnecessary connection overhead.

---

 Tech Stack

Technology| Purpose
Kotlin| Android application development
Jetpack / AndroidX| Application architecture
SQLite / Room| Local data storage
Firebase| Backend services & configuration
Cloudinary| Media/image infrastructure
TarsosDSP| Audio processing
Last.fm API| Music metadata & tags
LRCLIB| Lyrics
MusicBrainz| Music metadata
NewPipeExtractor| Stream extraction
Sentry| Crash & ANR monitoring
GitHub| Version control & project hosting

---

 Recommendation Dataset

The recommendation system was developed using a large-scale music dataset containing millions of tracks.

For mobile optimization, the dataset was processed and reduced into a much smaller recommendation database containing the most relevant tracks and metadata.

This allows VIN Music to perform recommendation calculations locally without requiring a massive cloud database for every recommendation request.

---

 Privacy

VIN Music is designed with a local-first approach wherever possible.

Listening-related information required for personalization can be processed locally on the device.

External services are used only where required for functionality such as:

- Lyrics
- Music metadata
- Stream resolution
- Remote configuration
- Media/image services

---


 Getting Started

Requirements

- Android Studio
- Android SDK
- Kotlin
- A Firebase project for services that require Firebase configuration

Clone the repository

git clone https://github.com/Vinrawa/Vin-music-v2.git
cd Vin-music-v2

Open the project in Android Studio and allow Gradle to synchronize.

Configure the required Firebase/API credentials according to the project's configuration before building.

---

 Roadmap

Music Library

- [x] Local playback
- [x] Downloads
- [x] Playlists
- [x] Listening history
- [ ] Advanced local folder browser
- [ ] Device playlist integration

Recommendations

- [x] TasteDNA
- [x] Track similarity
- [x] Smart Radio
- [ ] Improved personalization
- [ ] More advanced contextual recommendations

Playback

- [x] Background playback
- [x] Queue management
- [x] Offline playback
- [x] Stream resolution
- [ ] Additional audio effects

Lyrics

- [x] Automatic lyric lookup
- [x] Synchronized lyrics
- [ ] Improved lyric matching
- [ ] More offline lyric support

UI/UX

- [x] Modern dark UI
- [x] Polished playback experience
- [x] Dynamic artwork
- [ ] More animations
- [ ] Additional customization

---

 Why I Built VIN Music

Most music players solve one problem:

«"Play this song."»

VIN Music explores a different idea:

«"Understand what I listen to, help me discover what I might love next, and make the entire listening experience feel good."»

The project started as a music player and gradually evolved into an experiment involving:

- Recommendation systems
- Android media architecture
- Audio processing
- Information retrieval
- Music metadata
- Offline-first design
- Performance optimization
- UI/UX engineering

---

 What This Project Demonstrates

VIN Music is also a learning and engineering project covering:

- Android application development
- Kotlin
- Database design
- Recommendation algorithms
- Vector similarity
- API integration
- Asynchronous programming
- Media playback
- Network optimization
- Performance debugging
- ANR investigation
- UI/UX design

---

 Contributing

Contributions, ideas, bug reports, and suggestions are welcome.

If you find a bug or have an idea for improving VIN Music, feel free to open an issue.

---

 License

Add your preferred open-source license here.

---

 Author

Vin 

Built with curiosity, a lot of debugging, and probably an unreasonable number of late-night builds.

---

 If you find VIN Music interesting, consider starring the repository.
