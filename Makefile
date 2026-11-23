TOOLCHAIN_DIR ?= $(if $(wildcard /opt/toolchain/jdk/bin/java),/opt/toolchain,$(CURDIR)/build/toolchain)
PYTHON ?= $(if $(wildcard $(TOOLCHAIN_DIR)/venv/bin/python),$(TOOLCHAIN_DIR)/venv/bin/python,python3)

.PHONY: all apks icons test test-isolation test-runtime test-audio-state test-video-state test-reliability test-install test-weather test-features test-optical test-glass test-discovery test-windows test-release test-fdroid verify clean bootstrap sideload

all: apks verify

bootstrap:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" ./scripts/bootstrap-toolchain.sh

icons:
	python3 scripts/generate-icons.py

apks:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" ./scripts/build-all.sh

test: verify

test-isolation:
	$(PYTHON) scripts/test-app-isolation.py

test-runtime:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-runtime-repairs.py

test-audio-state:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-audio-state.py

test-video-state:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-video-state.py

test-reliability:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-reliability.py

test-install:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-install-maintenance.py

test-weather:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-weather.py

test-features:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-suite-features.py

test-optical:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-optical-transfer.py

test-glass:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-liquid-glass.py

test-discovery:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-media-discovery.py

test-windows:
	$(PYTHON) scripts/test-windows-tools.py

test-release:
	$(PYTHON) scripts/test-release-version.py

test-fdroid:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-fdroid-catalog.py

verify:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/verify-apks.py

sideload:
	./tools/usb-sideload-and-root.sh

clean:
	rm -rf build
