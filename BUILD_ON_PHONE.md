# Build on an Android phone with GitHub Actions

1. Sign in to GitHub in Chrome and create a new repository named `ServerOptimizer`.
2. Upload the contents of this folder to the repository root (the `pom.xml`, `src` folder, `.github` folder, README, etc.). Do not upload the outer `ServerOptimizer` folder as an extra nested folder.
3. Open the repository's **Actions** tab. If prompted, enable workflows.
4. Select **Build ServerOptimizer** and tap **Run workflow** (or wait for the first push workflow to run).
5. Open the completed green run, find **Artifacts**, and download `ServerOptimizer-1.1.2`.
6. Extract the downloaded artifact ZIP; inside is `ServerOptimizer-1.1.2.jar`.

If the run fails, open the failed run, copy the first Maven error and share it. Do not install the provisional JAR. Test the built JAR on a backup/staging server first.

Never publish access tokens, passwords, server IP credentials, or private configuration files in the repository. Keep the repository private if desired.
