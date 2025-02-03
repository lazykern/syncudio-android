#!/bin/bash

echo "🚀 Building release version of Syncudio..."

# Clean the project
echo "🧹 Cleaning project..."
./gradlew clean

# Build release variant
echo "🔨 Building release APK..."
./gradlew :app:assembleRelease

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "✅ Build completed successfully!"
    
    # Create releases directory if it doesn't exist
    mkdir -p releases
    
    # Copy the APK to the releases directory
    cp app/build/outputs/apk/release/app-release.apk releases/
    
    echo "📦 Release APK is available at: releases/app-release.apk"
else
    echo "❌ Build failed!"
    exit 1
fi 