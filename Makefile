TOOLCHAIN_DIR ?= $(if $(wildcard /opt/toolchain/jdk/bin/java),/opt/toolchain,$(CURDIR)/build/toolchain)
PYTHON ?= $(if $(wildcard $(TOOLCHAIN_DIR)/venv/bin/python),$(TOOLCHAIN_DIR)/venv/bin/python,python3)

.PHONY: all apks icons test test-isolation test-runtime test-install test-weather test-features test-fdroid verify clean bootstrap sideload

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

test-install:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-install-maintenance.py

test-weather:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-weather.py

test-features:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-suite-features.py

test-fdroid:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/test-fdroid-catalog.py

verify:
	TOOLCHAIN_DIR="$(TOOLCHAIN_DIR)" $(PYTHON) scripts/verify-apks.py

sideload:
	./tools/usb-sideload-and-root.sh

clean:
	rm -rf build
