SOURCE_DIR = src/
CLASS_DIR = bin/
SOURCES = $(subst $(SOURCE_DIR),,$(shell find $(SOURCE_DIR) -type f -name '*.java' -print ))
SOURCES += nl/utwente/ewi/fmt/EXPRES/Version.java
OBJECTS = $(addprefix $(CLASS_DIR), $(addsuffix .class, $(basename $(SOURCES))))
COMMIT = $(shell git rev-parse HEAD)
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=2
BASEVERSION=${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}
VERSIONSTRING="${BASEVERSION}"
ifeq ($(shell git status --porcelain),)
else
	VERSIONSTRING += + "+" + "$(COMMIT)".substring(0,8) + "-dirty"
endif
MAIN_CLASS = Main
ifeq (,$(wildcard .git))
	BUILD_DATE=$(shell git show --format=\%aD HEAD | head -1)
else
	BUILD_DATE=$(shell stat -c %y $(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java)
endif
JFLAGS = -Xlint:deprecation -g

.PHONY: jar all clean dir main deb rpm prep_package repro_jar

main: dir all

jar: DFTRES.jar

repro_jar: DFTRES.jar
	@echo 'UNZIP $@'
	@mkdir -p $(CLASS_DIR)/jar
	@(cd $(CLASS_DIR)/jar && unzip -q ../../DFTRES.jar)
	@find $(CLASS_DIR)/jar -exec touch --date='$(BUILD_DATE)' '{}' ';'
	@rm DFTRES.jar
	@echo 'ZIP $@'
	@(cd $(CLASS_DIR)/jar && find | sort | zip -Xoq9@ ../../DFTRES.jar)

DFTRES.jar: dir $(OBJECTS)
	@rm -rf $(CLASS_DIR)/jar
	@echo 'JAR   $@'
	@jar cfe DFTRES.jar $(MAIN_CLASS) -C $(CLASS_DIR) .

all: dir $(OBJECTS)

dir: $(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java
	@mkdir -p $(CLASS_DIR)

$(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java: FORCE
ifneq (,$(wildcard .git))
	@echo "package nl.utwente.ewi.fmt.EXPRES;" > $@
	@echo "public class Version {" >> $@
	@echo '	public static final String version = ${VERSIONSTRING};' >> $@
	@echo "}" >> $@
endif

$(CLASS_DIR)%.class: $(SOURCE_DIR)%.java
	@javac -Xlint:unchecked -sourcepath $(SOURCE_DIR) $(JFLAGS) -d $(CLASS_DIR) $<
	@echo JAVAC $(@:$(CLASS_DIR)%=%)

clean:
	@$(RM) -r $(CLASS_DIR)
	@echo 'RM    $(CLASS_DIR)'
	@$(RM) -r DFTRES.jar
	@echo 'RM    DFTRES.jar'

prep_package: FORCE
	@echo 'RM       pkgtmp'
	@$(RM) -rf pkgtmp
	@echo 'CHECKOUT pkgtmp'
	@git checkout-index -a --prefix=pkgtmp/dftres-${BASEVERSION}/
	@cp $(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java pkgtmp/dftres-$(BASEVERSION)/$(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES
	@echo 'CLEAN    pkgtmp'
	@$(RM) -rf pkgtmp/dftres-${BASEVERSION}/package
	@$(RM) -rf pkgtmp/dftres-${BASEVERSION}/.github
	@mkdir pkgtmp/dftres-${BASEVERSION}/package
	@cp -r package/DFTRES pkgtmp/dftres-${BASEVERSION}/package

deb: prep_package $(addprefix $(SOURCE_DIR), $(SOURCES))
	@cp -r package/debian pkgtmp/dftres-${BASEVERSION}
	@echo 'DPKG-BUILDPACKGE'
	@cd pkgtmp/dftres-${BASEVERSION} && dpkg-buildpackage -g -tc $(DPKG_FLAGS)
	@cp pkgtmp/dftres_* .

rpm: prep_package
	@mkdir pkgtmp/SOURCES
	@sed -e 's/<version>/${BASEVERSION}/' package/dftres.spec > pkgtmp/dftres.spec
	@echo "TAR dftres-${BASEVERSION}.tar.xz"
	@(cd pkgtmp && tar c dftres-${BASEVERSION}) | xz > pkgtmp/SOURCES/dftres-${BASEVERSION}.tar.xz
	@echo "RPMBUILD"
	@cd pkgtmp && rpmbuild -D'_topdir $(shell pwd)/pkgtmp' $(RPMBUILD_FLAGS) -ba dftres.spec
	@mv pkgtmp/RPMS/*/* .
	@mv pkgtmp/SRPMS/* .

FORCE:
