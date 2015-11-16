package averroes.frameworks;

import java.io.File;
import java.util.Collections;

import org.apache.commons.io.FileUtils;

import soot.ClassProvider;
import soot.G;
import soot.Scene;
import soot.SourceLocator;
import soot.options.Options;
import averroes.frameworks.options.FrameworksOptions;
import averroes.frameworks.soot.CodeGenerator;
import averroes.frameworks.soot.FrameworksClassProvider;
import averroes.util.TimeUtils;

/**
 * The main Averroes class.
 * 
 * @author Karim Ali
 * 
 */
public class Main {

	/**
	 * The main Averroes method.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			// Find the total execution time, instead of depending on the Unix
			// time command
			TimeUtils.splitStart();

			// Process the arguments
			FrameworksOptions.processArguments(args);

			// Reset Soot
			G.reset();

			// Create the output directory and clean up any class files in there
			FileUtils.forceMkdir(new File(FrameworksOptions.getOutputDirectory()));
			FileUtils.cleanDirectory(new File(FrameworksOptions.getOutputDirectory()));

			// Prepare the soot classpath
			TimeUtils.reset();
			System.out.println("");
			System.out.println("Preparing Averroes ...");
			FrameworksClassProvider provider = new FrameworksClassProvider();
			provider.prepareClasspath();

			// Set some soot parameters
			SourceLocator.v().setClassProviders(Collections.singletonList((ClassProvider) provider));
			Options.v().classes().addAll(provider.getClassNames());
			System.out.println(Options.v().soot_classpath());
			Options.v().set_full_resolver(true);
			Options.v().set_validate(true);

			// Load the necessary classes
			TimeUtils.reset();
			System.out.println("");
			System.out.println("Loading classes ...");
			Scene.v().loadNecessaryClasses();
			double soot = TimeUtils.elapsedTime();
			System.out.println("Soot loaded the input classes in " + soot + " seconds.");

			// Now let Averroes do its thing
			TimeUtils.reset();
			System.out.println("");
			System.out.println("$$$$$$$$$ " + Scene.v().getSootClass("java.lang.Throwable").getMethods());
			Scene.v().getClasses().forEach(CodeGenerator::transformSootClass);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}