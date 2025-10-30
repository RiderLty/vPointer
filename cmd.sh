sudo apt-get update 
sudo apt-get install -y wget unzip  
sudo apt install -y openjdk-11-jdk
wget https://dl.google.com/android/repository/commandlinetools-linux-8512546_latest.zip
mkdir -p android-sdk/cmdline-tools
unzip commandlinetools-linux-8512546_latest.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
export ANDROID_HOME=$PWD/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.0"
sdkmanager --licenses  # 接受所有许可证
wget https://services.gradle.org/distributions/gradle-7.5-bin.zip
sudo unzip -d /opt/gradle gradle-7.5-bin.zip
export GRADLE_HOME=/opt/gradle/gradle-7.5
export PATH=$PATH:$GRADLE_HOME/bin
gradle assembleDebug



docker build . -t builder -f ./Dockerfile.builder
docker run --rm -it -v ./:/application builder sh