package org.parchmentmc.lodestone.tasks;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.parchmentmc.feather.metadata.*;
import org.parchmentmc.feather.named.Named;
import org.parchmentmc.feather.util.SimpleVersion;
import org.parchmentmc.lodestone.asm.CodeCleaner;
import org.parchmentmc.lodestone.asm.CodeTree;
import org.parchmentmc.lodestone.asm.MutableClassInfo;
import org.parchmentmc.lodestone.converter.ClassConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class ExtractMetadataFromJarFiles extends ExtractMetadataTask
{
    public ExtractMetadataFromJarFiles()
    {
        this.getOutput().convention(getProject().getLayout().getBuildDirectory().dir(getName()).map(d -> d.file("metadata.json")));
    }

    @Override
    protected SourceMetadata extractMetadata(File clientJarFile) throws IOException {
        final File librariesDirectory = this.getLibraries().getAsFile().get();

        final CodeTree codeTree = new CodeTree();
        codeTree.load(clientJarFile.toPath(), false);

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("regex:.+\\.jar");
        try (Stream<Path> libraries = Files.find(librariesDirectory.toPath(), 999, (path, basicFileAttributes) -> basicFileAttributes.isRegularFile() && matcher.matches(path)))
        {
            for (Path libraryFile : libraries.collect(Collectors.toSet()))
            {
                codeTree.load(libraryFile, true);
            }
        }

        final Set<String> minecraftJarClasses = codeTree.getNoneLibraryClasses();
        final Map<String, MutableClassInfo> asmParsedClassInfo = minecraftJarClasses.stream().collect(Collectors.toMap(
                Function.identity(),
                codeTree::getClassMetadataFor
        ));

        final CodeCleaner codeCleaner = new CodeCleaner(codeTree);
        asmParsedClassInfo.values().forEach(codeCleaner::cleanClass);

        final ClassConverter classConverter = new ClassConverter();
        final Map<String, ClassMetadata> cleanedClassMetadata = minecraftJarClasses.stream().collect(Collectors.toMap(
                Function.identity(),
                name -> {
                    final MutableClassInfo classInfo = asmParsedClassInfo.get(name);
                    return classConverter.convert(classInfo);
                }
        ));

        final SourceMetadata baseDataSet = SourceMetadataBuilder.create()
                .withSpecVersion(SimpleVersion.of("1.0.0"))
                .withMinecraftVersion(getMcVersion().get())
                .withClasses(new LinkedHashSet<>(cleanedClassMetadata.values()));

        return adaptClassTypes(baseDataSet);
    }

    private static SourceMetadata adaptClassTypes(final SourceMetadata sourceMetadata) {
        return adaptInnerOuterClassList(sourceMetadata);
    }

    private static SourceMetadata adaptInnerOuterClassList(final SourceMetadata sourceMetadata) {
        final Map<Named, ClassMetadataBuilder> namedClassMetadataMap = sourceMetadata.getClasses()
                                                                         .stream()
                                                                         .collect(Collectors.toMap(
                                                                           WithName::getName,
                                                                           ClassMetadataBuilder::create
                                                                         ));

        namedClassMetadataMap.values().forEach(classMetadata -> {
            final Named outerName = classMetadata.getOwner();
            if (namedClassMetadataMap.containsKey(outerName)) {
                final ClassMetadataBuilder outerBuilder = namedClassMetadataMap.get(outerName);
                outerBuilder.addInnerClass(classMetadata);
            }
        });

        return SourceMetadataBuilder.create()
                 .withSpecVersion(sourceMetadata.getSpecificationVersion())
                 .withMinecraftVersion(sourceMetadata.getMinecraftVersion())
                 .withClasses(namedClassMetadataMap.values()
                                .stream()
                                .filter(classMetadataBuilder -> classMetadataBuilder.getOwner().isEmpty())
                                .map(ClassMetadataBuilder::build)
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                 )
                 .build();
    }

    @InputDirectory
    public abstract DirectoryProperty getLibraries();
}
