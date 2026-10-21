.PHONY: all apks icons test verify clean bootstrap sideload

all: apks verify

bootstrap:
	./scripts/bootstrap-toolchain.sh

icons:
	python3 scripts/generate-icons.py

apks:
	./scripts/build-all.sh

test: verify

verify:
	/opt/toolchain/venv/bin/python scripts/verify-apks.py

sideload:
	./tools/usb-sideload-and-root.sh

clean:
	rm -rf build
