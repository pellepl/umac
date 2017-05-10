BINARY = umac

############
#
# Paths
#
############

sourcedir = src
builddir = build

MKDIR = mkdir -p

###############
#
# Files and libs
#
###############

CFILES = \
	umac.c \
	umac_test.c \
	filetransfer/umac_filetransfer_tx.c \
	filetransfer/umac_filetransfer_rx.c \
	

INC = \
	-I./${sourcedir} \

LIBS = \
	-lpthread \

CFLAGS += \
	-Werror \
	-Wall -Wno-format-y2k -W -Wstrict-prototypes -Wmissing-prototypes \
	-Wpointer-arith -Wreturn-type -Wcast-qual -Wwrite-strings -Wswitch \
	-Wshadow -Wcast-align -Wchar-subscripts -Winline -Wnested-externs \
	-Wno-redundant-decls -Wno-unused-parameter -Wno-missing-prototypes -Wno-cast-qual \
	
CFLAGS += $(INC)
		
############
#
# Tasks
#
############

vpath %.c ${sourcedir}

OBJFILES = $(CFILES:%.c=${builddir}/%.o)
DEPFILES = $(CFILES:%.c=${builddir}/%.d)
ALLOBJFILES += $(OBJFILES)

all: ${builddir}/$(BINARY)

DEPENDENCIES = $(DEPFILES) 

# link object files, create binary
${builddir}/$(BINARY): $(ALLOBJFILES)
	@echo "... linking executable $@"
	@${CC} $(CFLAGS) -o $@ $(ALLOBJFILES) $(LFLAGS) $(LIBS) 

ifneq ($(MAKECMDGOALS),clean)
-include $(DEPENDENCIES)
endif

VERBOSE ?= 0

../test/%.test.__run : ../test/%.test
	@echo "$(notdir $<) running..."
	@${builddir}/$(BINARY) $< verbose=$(VERBOSE)
	@echo "$(notdir $<) OK"

test : ${builddir}/$(BINARY)
test : $(addsuffix .__run, $(wildcard ../test/*.test))

# compile c files
$(OBJFILES) : ${builddir}/%.o:%.c
	@echo "... compile $@"
	@${MKDIR} $(@D)
	@${CC} $(CFLAGS) -g -c -o $@ $<

# make dependencies
$(DEPFILES) : ${builddir}/%.d:%.c
	@${RM} -f $@; \
	${MKDIR} $(@D); \
	${CC} $(CFLAGS) -M $< > $@.$$$$; \
	sed 's,\($*\)\.o[ :]*, ${builddir}/\1.o $@ : ,g' < $@.$$$$ > $@; \
	${RM} -f $@.$$$$
		
.PHONY: all
all: ${builddir}/$(BINARY) 

.PHONY: clean
clean:
	@echo ... removing ${builddir}
	@${RM} -rf ${builddir}

.PHONY: info
info:
	@echo "*** CC ***"
	@echo ${CC}
	@echo
	@echo "*** CFLAGS ***"
	@echo ${CFLAGS}
	@echo
	@echo "*** LFLAGS ***"
	@echo ${LFLAGS}
	@echo
	@echo "*** LIBS ***"
	@echo ${LIBS}

