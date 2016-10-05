# ISRA
Intelligent Self Reliance Agent

An Experimental Artificial General Intelligence Architecture.  
Website: www.ogxis.com  
Get the thesis [here](https://dl.dropboxusercontent.com/s/ceu2p1asis2tlrx/isra.pdf?dl=0).

## License
Released under Apache License, Version 2.0

## Setup
### Third Party Libraries
* [commons-exec-1.3](https://commons.apache.org/proper/commons-exec/)
* [commons-io-2.4](http://commons.apache.org/proper/commons-io/)
* [concurrentlinkedhashmap-lru-1.4.jar](https://mvnrepository.com/artifact/com.googlecode.concurrentlinkedhashmap/concurrentlinkedhashmap-lru/1.4) required by orientDB
* [dejavu-master](https://github.com/worldveil/dejavu)
* [guava-19.0-rc2.jar](https://github.com/google/guava)
* [jung2-2_0_1](https://github.com/jrtom/jung)
* [jython-standalone-2.7.0.jar](http://www.jython.org/downloads.html)
* [kryo-3.0.3](https://github.com/EsotericSoftware/kryo)
* [opencv-3.0.0](http://opencv.org/downloads.html) uses opencv3.0.0.jar
* [orientdb-community-2.2.10 OR newer.](http://orientdb.com/download/)
* [yamlbeans-1.06](https://github.com/EsotericSoftware/yamlbeans)

Put all of them into ext/ folder.

### Internal Use Misc Library
* POInterchange.jar (Already included, used internally only)

### Hardware
Due to currently hardware path is hardcoded into the code and database, adding new devices requires modification to the source code.  
Current default setup is:
* 4 motor, support both input (feedback) and output, controlled via a raspberry pi.
* A visual input stream
* An audio input and output stream.

Update the dummy addresses to your actual device address.  
To add more devices, must create relevant classes in DB to store those data, it is not automated and has to be hardcoded into source code. Guide is inside source code.

Code other than input output of devices doens't have to be modified at all.


## Run
### Start Simulation
Start orientDB server.

Start *DBHierarchySetup*.  
Select option **Create Only** to setup database structure. Only needed to run once.

Start *StorageRegistrar*.  
Start *StartupSoft* on the target worker machine.  
Start *Console* OR *GuiConsole* to control workers.

At *console*:
* utnl (Update Target Node List to get all registered worker nodes from database)
* ltn (List Target Node to show selectable nodes and their current states)
* stn (Select Target Node to select which node the preceding command will route to)
* eb consoleScript/tb (ExecuteBulk testBulk.gcmd; Execute bulk commands to setup worker threads for the targetted machine)
* hcs (Halt Command Server, execute when wanted to stop simulation, this will halt the targetNode's startupSoft, essentially closing it down)
* hsr (Halt Storage Registrar, to shut it down cleanly)
* h (Halt this console)

### Visualize Result
* Make sure database is online.
* Start *GUIConsole*.
* At DBCredentials, select default to auto fill in login credentials.
* At Input Data Source -> Direct Fetch From DB, fill in the range wanted to analyse in form of GCA Rid. GCA is the timer in ISRA, each GCA has an absolute timestamp.
* Click Validate. (If success will auto switch to next window)
* Select the type of visualization, which will open another window.
* At the main window's timeline bar, click start to begin visualization.

### Continue After Halt
Follow the exact same procedure in **Start Simulation** section except the DBHierarchy setup part, it will continue on learning and previous data is not lost.

### Reset
Run *DBHierarchySetup*, select option **Drop and Recreate**.  
It will wipe out all previously learned data and return the database to a clean state equivalent to result of **Create Only**.


## Limitation
Learning is slow at first, but will speed up in the future as experiences increase (less unpredictable stuff encountered from time to time) in theory.  
The simulation is costly and doesn't guarantee 100% useful result for now.

Currently lack of resources to continue on developing the project and testing the theory.
