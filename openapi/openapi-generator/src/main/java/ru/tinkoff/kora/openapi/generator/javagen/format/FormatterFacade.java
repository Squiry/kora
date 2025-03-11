package ru.tinkoff.kora.openapi.generator.javagen.format;

import com.google.common.annotations.VisibleForTesting;
import com.palantir.goethe.GoetheException;

import java.lang.management.ManagementFactory;
import java.util.List;

public interface FormatterFacade {
    String formatSource(String className, String unformattedSource) throws GoetheException;

    static FormatterFacade create() {
        if (currentJvmHasExportArgs()) {
            return new DirectFormatterFacade();
        }
        return new BootstrappingFormatterFacade();
    }

    private static boolean currentJvmHasExportArgs() {
        var arguments = List.copyOf(ManagementFactory.getRuntimeMXBean().getInputArguments());
        return BootstrappingFormatterFacade.REQUIRED_EXPORTS.stream()
            .allMatch(required -> hasExport(arguments, required));
    }

    @VisibleForTesting
    static boolean hasExport(List<String> arguments, String moduleAndPackage) {
        var singleArgAddExport = "--add-exports=" + moduleAndPackage + "=ALL-UNNAMED";
        var multiArgAddExport = moduleAndPackage + "=ALL-UNNAMED";
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (singleArgAddExport.equals(argument)) {
                return true;
            }
            if (multiArgAddExport.equals(argument)) {
                if (i > 0 && "--add-exports".equals(arguments.get(i - 1))) {
                    return true;
                }
            }
        }
        return false;
    }

}
