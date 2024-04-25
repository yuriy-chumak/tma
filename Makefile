## prerequisites
PLATFORM ?= android-30

ANDROID_SDK?=/opt/android/sdk
ANDROID_NDK?=/opt/android/ndk
BUILD_TOOLS?=$(ANDROID_SDK)/build-tools/29.0.2

ifeq ("$(wildcard $(ANDROID_SDK)/)","")
$(error ANDROID_SDK not set or invalid!)
endif

#ifeq ("$(wildcard $(ANDROID_NDK)/)","")
#$(error ANDROID_NDK not set or invalid!)
#endif

## project build structure
$(shell mkdir -p obj dex lib)

## android builds
SHELL := /bin/sh
PATH = $(shell printenv PATH):$(ANDROID_SDK)/platform-tools

## build ol libraries
## tbd.

## build java project
dex/classes.dex: $(shell find src/ -name '*.java')
	mkdir -p res # generate resources
	$(BUILD_TOOLS)/aapt package -f -m \
	      -S res -J src -M AndroidManifest.xml \
	      -I $(ANDROID_SDK)/platforms/$(PLATFORM)/android.jar
	mkdir -p obj # compile java files
	javac -verbose -source 1.8 -target 1.8 -d obj \
	      -bootclasspath jre/lib/rt.jar \
	      -classpath $(ANDROID_SDK)/platforms/$(PLATFORM)/android.jar:obj \
	      -sourcepath src $^
	mkdir -p dex # create classes.dex
	$(BUILD_TOOLS)/dx --verbose --dex --output=$@ obj

debug.apk: dex/classes.dex debug.keystore
	$(BUILD_TOOLS)/aapt package -f \
	      -M AndroidManifest.xml -S res -A assets \
	      -I $(ANDROID_SDK)/platforms/$(PLATFORM)/android.jar \
	      -F $@ dex
	## todo: add shared libraries to apk
	## $BUILD_TOOLS/aapt add debug.apk `find -L lib/ -name *.so`

debug.final.apk: debug.apk
	$(BUILD_TOOLS)/zipalign -f 4 debug.apk debug.final.apk
	jarsigner -keystore debug.keystore -storepass debug33 -keypass debug33 \
	          -signedjar $@ $@ projectKey

debug.keystore:
	keytool -genkeypair -validity 1000 \
	        -dname "CN=debug,O=Android,C=ES" \
	        -keystore $@ \
	        -storepass 'debug33' -keypass 'debug33' \
	        -alias projectKey -keyalg RSA

# build automation
.PHONY: build all debug

all: build

build: debug.final.apk
clean:
	rm -rf dex/* lib/* obj/*
	find src -name "R.java" -exec rm {} \;
	rm -f debug.apk debug.final.apk

install: build
	adb -d install debug.final.apk
	# grant default permission(s)
	adb shell pm grant com.track.my.ass android.permission.READ_EXTERNAL_STORAGE
uninstall:
	adb -d uninstall com.track.my.ass

# run and stop app on device
start:
	adb shell am start -n com.track.my.ass/com.track.my.ass.TheActivity
stop:
	adb shell am force-stop com.track.my.ass
restart:
	$(MAKE) stop
	$(MAKE) start

debug:
	$(MAKE) build
	$(MAKE) install
	$(MAKE) start
