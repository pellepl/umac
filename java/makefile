binary = jumac-test.jar
srcdir = src
builddir = build
JFLAGS = -g -cp ${srcdir} -d ${builddir}
JC = javac
VERBOSE ?= 0

SRC = \
	${srcdir}/com/pelleplutt/jumac/Jumac.java \
	${srcdir}/com/pelleplutt/jumac/test/Test.java \
	
CLASSES = $(SRC:%.java=${builddir}/%.class)

${builddir}/$(binary): .paths $(CLASSES)
	@echo "... building $@"
	@jar cvfe $@ com.pelleplutt.jumac.test.Test -C ${builddir} . > /dev/null
	
$(CLASSES) : ${builddir}/%.class:%.java
	@echo "... compiling $@"
	@$(JC) $(JFLAGS) $<

.paths:
	@mkdir -p ${builddir}

../test/%.test.__run : ${builddir}/$(binary)
../test/%.test.__run : ../test/%.test
	@echo "$(notdir $<) running..."
	@java -jar ${builddir}/$(binary) $< verbose=$(VERBOSE)
	@echo "$(notdir $<) OK"

test : ${builddir}/$(binary)
test : $(addsuffix .__run, $(wildcard ../test/*.test))


clean:
	rm -rf ${builddir}

