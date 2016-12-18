package startup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

import console.Console;
import storageRegistrar.StorageRegistrar;

/**
 * Simple startup manager, to select which application to run.
 */
public class StartupManager {
	public static void main(String[] args) {
		boolean programAlreadySelected = false;
		ArrayList<String> finalArgs = new ArrayList<String>(Arrays.asList(args));
		String userSelection = "";

		if (args.length > 0) {
			if (args[0].equals("-0") || args[0].equals("-1") || args[0].equals("-2") || args[0].equals("-3") || args[0].equals("-4")) {
				programAlreadySelected = true;
				//Skip the '-' mark.
				userSelection = args[0].substring(1);

				//Remove first argument so it don't get passed into preceding program.
				ArrayList<String> temp = new ArrayList<String>();
				for (int i=1; i<finalArgs.size(); i++) {
					temp.add(finalArgs.get(i));
				}
				finalArgs = temp;
			}
		}

		//If user already specified what they want to run, run it directly and skip all the crap.
		if (!programAlreadySelected) {
			//Available applications.
			System.out.println("ISRA Program selector, given arguments will be forwarded to selected program if any.");
			System.out.println("Can optionally specify shortcut to skip selection prompt and start the intended program directly by ");
			System.out.println("java -jar ISRA.jar [-0/-1/-2/-3/-4] [....... (parameter to be passed to program)]");
			System.out.println("");

			if (args.length == 0) {
				System.out.println("NOTE: NO ARGUMENT RECEIVED!");
			}
			else {
				System.out.println("Parameter to be passed to preceding program:");
				for (int i=0; i<args.length; i++) {
					System.out.println(i + ". " + args[i]);
				}
			}

			System.out.println("");
			System.out.println("Select which application to run by its initials:");
			System.out.println("0. DBHierarchySetup");
			System.out.println("1. StorageRegistrar");
			System.out.println("2. StartupSoft");
			System.out.println("3. Console");
			System.out.println("4. GUIConsole");
			System.out.println("'q' to quit");
			System.out.println("'h' More info....");
			System.out.println("");

			Scanner scanner = new Scanner(System.in);
			while (true) {
				System.out.print("Selection: ");
				userSelection = scanner.nextLine();

				if (userSelection.equals("q"))
					break;

				if (userSelection.equals("0") || userSelection.equals("1") || userSelection.equals("2") || userSelection.equals("3") || userSelection.equals("4"))
					break;

				if (userSelection.equals("h")) {
					System.out.println("=================================");
					System.out.println("Expected parameters from each programs:");
					System.out.println("DBHierarchySetup");
					System.out.println("Usage: progName protocolAndAddr databaseNameYouWanted username serverPassword"
					+ " Example: progName remote:localhost databaseName root ASHDHSFHSHHDHHSD1231412; Note that it will auto append a '/' between"
					+ "args[0] and args[1] to form remote:localhost/databaseName which is the format the DB wanted.");
					System.out.println("");
					System.out.println("StorageRegistrar");
					System.out.println("Usage: programName CustomConfigFile");
					System.out.println("");
					System.out.println("StartupSoft");
					System.out.println("Usage: progName | programName [CustomConfigFile]\nFirst will use default file 'config/startupSoftConfig.yml'");
					System.out.println("");
					System.out.println("Console");
					System.out.println("Usage: ProgName configFile.yml");
					System.out.println("");
					System.out.println("GUIConsole");
					System.out.println("No parameter required. Experimental.");
					System.out.println("=================================");
					System.out.println("");
				}
			}
			scanner.close();

		}

		//Convert the arraylist back to array and pass to preceding programs.
		if (userSelection.equals("0")) {
			DBHierarchySetup.main(finalArgs.toArray(new String[0]));
		}

		else if (userSelection.equals("1")) {
			StorageRegistrar.main(finalArgs.toArray(new String[0]));
		}

		else if (userSelection.equals("2")) {
			StartupSoft.main(finalArgs.toArray(new String[0]));
		}

		else if (userSelection.equals("3")) {
			Console.main(finalArgs.toArray(new String[0]));
		}

		else if (userSelection.equals("4")) {
			guiConsole.Main.main(finalArgs.toArray(new String[0]));
		}
	}
}
