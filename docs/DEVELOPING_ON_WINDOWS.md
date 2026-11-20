# Developing and Building Caerbannog on Windows

Welcome to Caerbannog! This guide is written for beginners to help you set up your development environment, build the project, run the desktop application, run the tests, and package Windows installers.

---

## 1. What is Caerbannog?

Caerbannog is a desktop management application for **RabbitMQ Streams**, built with:
- **Kotlin**: Modern statically-typed programming language targeting the JVM.
- **Compose Multiplatform**: Declarative UI framework by JetBrains based on Google Jetpack Compose.
- **Gradle**: Build automation tool used to compile and test the code.
- **go-task (Taskfile)**: Convenient task runner for daily developer workflows.

---

## 2. Tools & Prerequisites (with Web Addresses)

You will need the following tools installed on your Windows machine:

| Component | Purpose | Official Website & Download Link |
| :--- | :--- | :--- |
| **Git for Windows** | Source control / clone the repository | [https://gitforwindows.org/](https://gitforwindows.org/)<br>[https://git-scm.com/download/win](https://git-scm.com/download/win) |
| **Java Development Kit (JDK 21 LTS)** | Java Runtime & Compiler (Temurin OpenJDK 21) | [https://adoptium.net/temurin/releases/?version=21](https://adoptium.net/temurin/releases/?version=21) |
| **Taskfile (`go-task`)** | CLI task runner for easy project shortcuts | [https://taskfile.dev/](https://taskfile.dev/)<br>Installation guide: [https://taskfile.dev/installation/#windows](https://taskfile.dev/installation/#windows) |
| **Docker Desktop for Windows** | Local RabbitMQ container for broker tests & dev seed | [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/) |
| **IntelliJ IDEA** | Recommended IDE with Kotlin & Compose support | [https://www.jetbrains.com/idea/download/](https://www.jetbrains.com/idea/download/) *(Community Edition is free)* |
| **Kotlin Language Reference** | Language docs & learning resources | [https://kotlinlang.org/](https://kotlinlang.org/) |
| **Compose Multiplatform** | Desktop UI framework documentation | [https://www.jetbrains.com/lp/compose-multiplatform/](https://www.jetbrains.com/lp/compose-multiplatform/) |
| **WiX Toolset v3** *(Optional)* | Required only for building the `.msi` Windows installer | [https://wixtoolset.org/releases/](https://wixtoolset.org/releases/)<br>[https://github.com/wixtoolset/wix3/releases](https://github.com/wixtoolset/wix3/releases) |

---

## 3. Quick Setup using Windows Package Manager (`winget`)

The fastest way to install everything on Windows 10 or 11 is using **PowerShell** (run as Administrator) with `winget`:

```powershell
# 1. Install Git
winget install --id Git.Git -e --source winget

# 2. Install Eclipse Temurin OpenJDK 21 (LTS)
winget install --id EclipseAdoptium.Temurin.21.JDK -e --source winget

# 3. Install Task (go-task)
winget install --id Task.Task -e --source winget

# 4. Install Docker Desktop (requires WSL 2)
winget install --id Docker.DockerDesktop -e --source winget

# 5. Install IntelliJ IDEA Community Edition (or Ultimate)
winget install --id JetBrains.IntelliJIDEA.Community -e --source winget

# 6. (Optional) Install WiX Toolset for packaging Windows .msi installers
winget install --id WiX.Toolset -e --source winget
```

> [!NOTE]
> Restart your PowerShell or Windows Terminal window after installing so the new environment variables (`PATH`, `JAVA_HOME`) take effect.

---

## 4. Verifying Your Environment

Open **PowerShell** or **Windows Terminal** and run:

```powershell
# Check Git
git --version

# Check Java version (must be Java 21)
java -version

# Check Taskfile runner
task --version

# Check Docker (make sure Docker Desktop is launched and running)
docker --version
```

If `java -version` does not output version `21`, set your `JAVA_HOME` environment variable to your JDK 21 installation directory (typically `C:\Program Files\Eclipse Adoptium\jdk-21.*`).

---

## 5. Cloning and Opening the Project

1. Open PowerShell and navigate to your workspace folder:
   ```powershell
   cd C:\Projects
   git clone https://github.com/your-org/Caerbannog.git
   cd Caerbannog
   ```

2. Open the project in **IntelliJ IDEA**:
   - Launch IntelliJ IDEA.
   - Choose **Open** (or **File > Open...**).
   - Select the `Caerbannog` root folder containing `settings.gradle.kts`.
   - When prompted, choose **Trust Project** and let Gradle sync dependencies automatically.

---

## 6. How to Build and Run

You can build and run Caerbannog either using the included **Gradle Wrapper** (`.\gradlew.bat`) or using **Task** (`task`).

### Run the Desktop Application
To launch the desktop UI directly:
```powershell
# Using Task:
task run

# Or using Gradle Wrapper directly:
.\gradlew.bat :desktopApp:run
```

### Run the Fast Test Suite (No Docker Needed)
Runs all unit, UI, and in-memory acceptance tests across all modules:
```powershell
# Using Task:
task test

# Or using Gradle Wrapper directly:
.\gradlew.bat test
```

### Run the Docker-based Broker Tests
If Docker Desktop is running, you can run the integration tests against a real RabbitMQ 4 broker:
```powershell
# Using Task:
task test:broker

# Or using Gradle Wrapper directly:
.\gradlew.bat :data-rabbit:brokerTest :data-rabbit:brokerSpikeTest --continue
```

---

## 7. Local RabbitMQ Development Environment

Caerbannog includes a preconfigured local RabbitMQ broker environment with streams and test TLS certificates.

### Start the Local Broker
```powershell
task dev:up
```
This starts RabbitMQ 4.x in Docker with:
- Stream TLS listener: port `5551`
- Stream plain listener: port `5552`
- Management UI (HTTPS): `https://localhost:15671` (username: `admin`, password: `dev-password`)
- Management UI (HTTP): `http://localhost:15672`

### Seed the Broker with Test Messages
To generate sample streams with 1,000 messages each:
```powershell
task dev:seed -- --password dev-password
```

To keep active publishers and consumers connected so the **Publishers and Consumers** tab displays live data:
```powershell
task dev:seed -- --password dev-password --hold
```
*(Press `Ctrl+C` in PowerShell to stop the hold).*

### Reset the Broker
To wipe all broker state and recreate a clean environment:
```powershell
task dev:reset
```

---

## 8. Packaging the Windows Installer (`.msi`)

To create an installer package for Windows:
1. Ensure **WiX Toolset v3** is installed and in your `PATH`.
2. Run:
   ```powershell
   # Using Task:
   task package:msi

   # Or using Gradle Wrapper:
   .\gradlew.bat :desktopApp:packageMsi
   ```
3. The generated installer will be located in `desktopApp/build/compose/binaries/main/msi/`.

---

## 9. Common Windows Troubleshooting & Tips

- **PowerShell Execution Policy Error**:
  If PowerShell blocks running scripts with `...cannot be loaded because running scripts is disabled on this system`, run:
  ```powershell
  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
  ```
- **Git Line Endings (`CRLF` vs `LF`)**:
  To prevent Git from altering Unix line endings across Kotlin and Gradle files, set:
  ```powershell
  git config --global core.autocrlf input
  ```
- **Gradle Daemon Locks on Windows**:
  Sometimes Windows file locks prevent deleting `build\` directories. If a build task fails with a file lock error, stop any running Gradle background daemons:
  ```powershell
  .\gradlew.bat --stop
  ```
- **Docker Desktop WSL 2 Backend**:
  Ensure Docker Desktop is set to use the **WSL 2 based engine** under **Docker Settings > General**.
