# 📦 Apk Station

[![Project Status](https://img.shields.io/badge/Status-Active%20Development-brightgreen)]()
[![Language](https://img.shields.io/badge/Language-Kotlin-purple)]()
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)]()

**Apk Station** is an open-source, privacy-first alternative app store for Android.

* 🔐 Zero tracking or telemetry
* 📱 Millions of free apps available
* 🔄 Automatic updates
* 🎯 Rob Braxman curated selections
* 🌐 Powered by Lunar Network
* 🛡️ Signature verification

---

## Overview

Apk Station provides a complete app store experience without compromising your privacy. Discover and install millions of **free applications** without tracking, data collection, or telemetry.

> **100% Free Apps**
> 
> Apk Station exclusively offers free applications. We do not host or distribute paid apps.

### Core Features

* **Personalized Homepage** — Discover new apps with tailored recommendations
* **Advanced Search** — Find exactly what you need with powerful search and category browsing
* **Detailed App Pages** — Complete information including privacy details for every app
* **Offline Mode** — Browse previously synced apps without an internet connection
* **FOSS Priority** — Open source focus with verified free and open source software
* **Rob Braxman Selections** — Curated privacy-focused apps featured in his videos

---

## 🛠️ Technical Stack

| Component    | Technology                                 |
| ------------ | ------------------------------------------ |
| Platform     | Native Android (Kotlin)                    |
| Backend      | Lunar Network (decentralized distribution) |
| Database     | SQLite (local app metadata)                |
| Verification | Signature verification                     |
| Updates      | Background service                         |
| Build System | Gradle with Kotlin DSL                     |

---

## 🚀 Getting Started

### Download Apk Station

* [Official Website](https://apkstation.braxtech.net)
* [GitHub Releases](https://github.com/braxtechnologies/Apk-Station/releases)
* F-Droid (coming soon)

### Quick Setup

1. **Download and install the APK**
2. **Grant permissions when prompted**
3. **Wait for initial repository sync**
4. **Start browsing and installing apps**
5. **Configure automatic updates in settings**

---

## 📋 Featured Categories

| Category               | Description                                   |
| ---------------------- | --------------------------------------------- |
| **Communication**      | Messaging, email, and VoIP apps               |
| **Security & Privacy** | VPNs, password managers, and encryption tools |
| **Productivity**       | Office suites, note-taking, and organization  |
| **Multimedia**         | Media players, photo editors, and streaming   |
| **Development**        | IDEs, terminal emulators, and dev tools       |
| **System Tools**       | File managers, backup tools, and utilities    |

---

## 👨‍💻 For Developers

### Requirements

Before building from source, ensure you have:

* **Java Development Kit (JDK) 17** or higher
* **Android SDK** with API level 30 or higher
* **Git** for cloning the repository

#### Installing JDK 17

**macOS (using Homebrew):**
```bash
brew install openjdk@17
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install openjdk-17-jdk
```

**Windows:**
Download and install from [Oracle](https://www.oracle.com/java/technologies/downloads/#java17) or [Adoptium](https://adoptium.net/)

**Verify Installation:**
```bash
java -version
# Should show: openjdk version "17.x.x" or higher
```

### Building from Source

```bash
git clone https://github.com/braxtechnologies/Apk-Station.git
cd Apk-Station
./gradlew assembleRelease
```

The built APK will be located at `app/build/outputs/apk/release/`

### Project Structure

```
Apk-Station/
├── app/                          # Main application module
│   ├── src/main/
│   │   ├── java/com/brax/apkstation/
│   │   │   ├── app/              # Application and activity classes
│   │   │   ├── data/             # Data layer (repositories, workers, network)
│   │   │   ├── domain/           # Domain models
│   │   │   ├── presentation/     # UI layer (ViewModels, Composables)
│   │   │   └── utils/            # Utility classes
│   │   ├── res/                  # Resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

### Key Technologies

* **Jetpack Compose** — Modern declarative UI
* **Hilt** — Dependency injection
* **WorkManager** — Background task scheduling
* **Room** — Local database
* **Retrofit** — Network requests
* **Coil** — Image loading

---

## 🔒 Privacy

Apk Station follows a **zero-knowledge, privacy-first** approach:

* ✅ No account required (use anonymously)
* ✅ No analytics or telemetry
* ✅ No ads or tracking
* ✅ No data collection on app usage
* ✅ All data stays local on your device

---

## 🗺️ Roadmap

### Coming Soon

#### 🔗 Multi-Repository Support

Add custom repositories from multiple platforms:

* **GitHub** — Direct integration with GitHub releases
* **GitLab** — Support for GitLab package registries
* **Forgejo (Codeberg)** — Decentralized Git hosting support
* **F-Droid** — Official F-Droid repository support
* **Third Party F-Droid Repos** — Add community F-Droid repositories
* **IzzyOnDroid** — Popular third-party Android app repository
* **SourceHut** — Support for sr.ht hosted projects

#### ✔️ App Signing Certificate Verifier

Easily verify that your apps are genuine! AppVerifier compares app signatures against provided or internal database hashes.

**Features:**
* Share verification info with others
* Receive and validate verification info from trusted sources
* Automatic verification status checking
* Simple, user-friendly verification process

#### 🛡️ Privacy Score

Comprehensive privacy scoring system to help you make informed decisions about which apps to install.

---

## 🤝 Contributing

We welcome contributions!

* **Code** — Submit bug fixes and new features
* **App Reviews** — Help review submitted applications
* **Documentation** — Improve our documentation
* **Translation** — Translate Apk Station to your language
* **Testing** — Test beta releases and report bugs

See our [Contributing Guidelines](https://opensource-dev.braxtech.net/apps/apk-station/) for details.

---

## 📞 Support

* **Documentation**: [opensource-dev.braxtech.net/apps/apk-station](https://opensource-dev.braxtech.net/apps/apk-station/)
* **Community Forum**: [community.braxtech.net](https://community.braxtech.net)
* **Issue Tracker**: [GitHub Issues](https://github.com/braxtechnologies/Apk-Station/issues)
* **Security**: security@braxtech.net

---

## 📜 License

Apk Station is released under the [GPL-3.0 License](LICENSE).

---

## 🌟 About Brax Technologies

Apk Station is developed by [Brax Technologies](https://braxtech.net), a company focused on privacy-first mobile technology and solutions.

Learn more:
* [Brax Technologies](https://braxtech.net)
* [Rob Braxman Tech YouTube Channel](https://youtube.com/@robbraxmantech)
* [Open Source Documentation](https://opensource-dev.braxtech.net)

---

Made with ❤️ by the Brax Technologies community
