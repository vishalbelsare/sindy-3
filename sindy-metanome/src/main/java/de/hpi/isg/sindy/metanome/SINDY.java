package de.hpi.isg.sindy.metanome;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import de.hpi.isg.sindy.core.Sindy;
import de.hpi.isg.sindy.metanome.properties.MetanomeProperty;
import de.hpi.isg.sindy.metanome.properties.MetanomePropertyLedger;
import de.hpi.isg.sindy.searchspace.NaryIndRestrictions;
import de.hpi.isg.sindy.util.IND;
import de.metanome.algorithm_integration.AlgorithmConfigurationException;
import de.metanome.algorithm_integration.AlgorithmExecutionException;
import de.metanome.algorithm_integration.ColumnIdentifier;
import de.metanome.algorithm_integration.ColumnPermutation;
import de.metanome.algorithm_integration.algorithm_types.FileInputParameterAlgorithm;
import de.metanome.algorithm_integration.algorithm_types.InclusionDependencyAlgorithm;
import de.metanome.algorithm_integration.algorithm_types.IntegerParameterAlgorithm;
import de.metanome.algorithm_integration.algorithm_types.StringParameterAlgorithm;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirement;
import de.metanome.algorithm_integration.configuration.ConfigurationRequirementFileInput;
import de.metanome.algorithm_integration.configuration.ConfigurationSettingFileInput;
import de.metanome.algorithm_integration.input.FileInputGenerator;
import de.metanome.algorithm_integration.input.InputGenerationException;
import de.metanome.algorithm_integration.input.RelationalInput;
import de.metanome.algorithm_integration.result_receiver.InclusionDependencyResultReceiver;
import de.metanome.algorithm_integration.results.InclusionDependency;
import de.metanome.backend.input.file.DefaultFileInputGenerator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntCollection;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;

import java.io.File;
import java.util.*;

/**
 * Metanome interface for the {@link Sindy} algorithm.
 */
public class SINDY implements InclusionDependencyAlgorithm,
        StringParameterAlgorithm, IntegerParameterAlgorithm, FileInputParameterAlgorithm {

    private FileInputGenerator[] fileInputGenerators;

    private InclusionDependencyResultReceiver resultReceiver;

    /**
     * @see de.hpi.isg.sindy.core.Sindy#maxColumns
     */
    @MetanomeProperty
    private int maxColumns = -1;

    /**
     * @see de.hpi.isg.sindy.core.Sindy#sampleRows
     */
    @MetanomeProperty
    protected int sampleRows = -1;

    /**
     * @see de.hpi.isg.sindy.core.Sindy#isDropNulls
     */
    @MetanomeProperty
    private boolean isDropNulls;

    /**
     * @see de.hpi.isg.sindy.core.Sindy#isNotUseGroupOperators
     */
    @MetanomeProperty
    private boolean isNotUseGroupOperators;

    /**
     * @see Sindy#maxArity
     */
    @MetanomeProperty
    private int maxArity = -1;

    /**
     * @see de.hpi.isg.sindy.core.AbstractSindy#naryIndRestrictions
     */
    @MetanomeProperty
    protected String naryIndRestrictions = NaryIndRestrictions.NO_REPETITIONS.name();

    /**
     * The number of bits used to encode columns.
     */
    @MetanomeProperty
    private int numColumnBits = 16;

    /**
     * The parallelism to use for Flink jobs. {@code -1} indicates default parallelism.
     */
    @MetanomeProperty
    private int parallelism = -1;

    /**
     * An optional {@code host:port} specification of a remote Flink master.
     */
    @MetanomeProperty
    private String flinkMaster = null;

    /**
     * An optional Flink configuration file.
     */
    @MetanomeProperty
    private String flinkConfig = null;


    /**
     * Keeps track of the configuration of this algorithm.
     */
    private MetanomePropertyLedger propertyLedger;

    @Override
    public void setResultReceiver(InclusionDependencyResultReceiver inclusionDependencyResultReceiver) {
        this.resultReceiver = inclusionDependencyResultReceiver;
    }

    @Override
    public ArrayList<ConfigurationRequirement<?>> getConfigurationRequirements() {

        ArrayList<ConfigurationRequirement<?>> configurationRequirements = new ArrayList<>();
        this.getPropertyLedger().contributeConfigurationRequirements(configurationRequirements);
        ConfigurationRequirementFileInput inputFiles = new ConfigurationRequirementFileInput("inputFiles");
        inputFiles.setRequired(true);
        configurationRequirements.add(inputFiles);
        return null;
    }

    private MetanomePropertyLedger getPropertyLedger() {
        if (this.propertyLedger == null) {
            try {
                this.propertyLedger = MetanomePropertyLedger.createFor(this);
            } catch (AlgorithmConfigurationException e) {
                throw new RuntimeException(e);
            }
        }
        return this.propertyLedger;
    }

    @Override
    public void execute() throws AlgorithmExecutionException {
        // Index the input files.
        Int2ObjectMap<SINDY.Table> indexedInputTables = indexTables(this.fileInputGenerators, this.numColumnBits);
        Int2ObjectMap<String> indexedInputFiles = new Int2ObjectOpenHashMap<>();
        for (Map.Entry<Integer, SINDY.Table> entry : indexedInputTables.entrySet()) {
            indexedInputFiles.put(entry.getKey(), entry.getValue().url);
        }

        // Set up Flink.
        ExecutionEnvironment executionEnvironment = this.createExecutionEnvironment();

        // Configure Sindy.
        Sindy sindy = new Sindy(indexedInputFiles, this.numColumnBits, executionEnvironment, ind -> {
        });
        sindy.setMaxArity(this.maxArity);
        if (this.fileInputGenerators[0] instanceof DefaultFileInputGenerator) {
            DefaultFileInputGenerator fileInputGenerator = (DefaultFileInputGenerator) this.fileInputGenerators[0];
            ConfigurationSettingFileInput setting = fileInputGenerator.getSetting();
            sindy.setFieldSeparator(setting.getSeparatorAsChar());
            sindy.setQuoteChar(setting.getQuoteCharAsChar());
            sindy.setNullString(setting.getNullValue());
        } else {
            System.err.println("Could not read CSV settings from Metanome configuration.");
        }
        sindy.setDropNulls(this.isDropNulls);
        sindy.setSampleRows(this.sampleRows);
        sindy.setMaxColumns(this.maxColumns);
        sindy.setNotUseGroupOperators(this.isNotUseGroupOperators);
        sindy.setOnlyCountInds(false);
        for (NaryIndRestrictions indRestrictions : NaryIndRestrictions.values()) {
            if (this.naryIndRestrictions.equalsIgnoreCase(indRestrictions.name())) {
                sindy.setNaryIndRestrictions(indRestrictions);
                break;
            }
        }

        // Run Sindy.
        sindy.run();

        // Translate the INDs.
        int columnBitMask = -1 >>> (Integer.SIZE - this.numColumnBits);
        for (IND ind : sindy.getConsolidatedINDs()) {
            InclusionDependency inclusionDependency = this.translate(ind, indexedInputTables, columnBitMask);
            this.resultReceiver.receiveResult(inclusionDependency);
        }
    }

    /**
     * Create a {@link ExecutionEnvironment} according to the configuration of this instance.
     *
     * @return the readily configured {@link ExecutionEnvironment}
     */
    private ExecutionEnvironment createExecutionEnvironment() {
        // Load a config if any.
        Configuration flinkConfiguration = this.flinkConfig != null
                ? parseTypeSafeConfig(new File(this.flinkConfig))
                : new Configuration();
        if (this.parallelism != -1) flinkConfiguration.setInteger(ConfigConstants.DEFAULT_PARALLELISM_KEY, this.parallelism);

        // Create a default or a remote execution environment.
        ExecutionEnvironment executionEnvironment;
        if (this.flinkMaster == null) {
            executionEnvironment = ExecutionEnvironment.createLocalEnvironment(flinkConfiguration);
        } else {
            String[] hostAndPort = this.flinkMaster.split(":");
            Set<String> jars = new HashSet<>();
            // Get the SINDY jar.
            jars.add(this.getClass().getProtectionDomain().getCodeSource().getLocation().getFile());
            // Get the fastutils jar.
            jars.add(IntCollection.class.getProtectionDomain().getCodeSource().getLocation().getFile());
            executionEnvironment = ExecutionEnvironment.createRemoteEnvironment(
                    hostAndPort[0],
                    Integer.parseInt(hostAndPort[1]),
                    flinkConfiguration,
                    jars.toArray(new String[jars.size()])
            );
        }

        if (this.parallelism != -1) executionEnvironment.setParallelism(this.parallelism);

        return executionEnvironment;
    }

    /**
     * Parse a Typesafe {@link Config} file.
     *
     * @param configFile the {@link File} to parse
     * @return a Flink {@link Configuration}
     */
    private static Configuration parseTypeSafeConfig(File configFile) {
        Configuration flinkConfiguration = new Configuration();
        Config typesafeConfig = ConfigFactory.parseFile(configFile);
        for (Map.Entry<String, ConfigValue> entry : typesafeConfig.entrySet()) {
            String key = entry.getKey();
            ConfigValue value = entry.getValue();
            switch (value.valueType()) {
                case BOOLEAN:
                    flinkConfiguration.setBoolean(key, (Boolean) value.unwrapped());
                    break;
                case NUMBER:
                    Number number = (Number) value.unwrapped();
                    if (number instanceof Float) {
                        flinkConfiguration.setFloat(key, number.floatValue());
                    } else if (number instanceof Double) {
                        flinkConfiguration.setDouble(key, number.doubleValue());
                    } else if (number instanceof Long) {
                        flinkConfiguration.setLong(key, number.longValue());
                    } else {
                        flinkConfiguration.setInteger(key, number.intValue());
                    }
                    break;
                case STRING:
                    flinkConfiguration.setString(key, (String) value.unwrapped());
                    break;
                default:
                    throw new IllegalArgumentException(String.format(
                            "Unsupported value type '%s' for key '%s'.",
                            value.valueType(), key
                    ));
            }
        }
        return flinkConfiguration;
    }

    /**
     * Translates an {@link IND} to a {@link InclusionDependency}.
     *
     * @param ind                that should be translated
     * @param indexedInputTables the indexed tables
     * @param columnBitMask      marks the column bits in the column IDs
     * @return the {@link InclusionDependency}
     */
    private InclusionDependency translate(IND ind, Int2ObjectMap<Table> indexedInputTables, int columnBitMask) {
        InclusionDependency inclusionDependency;
        if (ind.getArity() == 0) {
            inclusionDependency = new InclusionDependency(
                    new ColumnPermutation(), new ColumnPermutation()
            );
        } else {
            int depMinColumnId = ind.getDependentColumns()[0] & ~columnBitMask;
            int depTableId = depMinColumnId | columnBitMask;
            SINDY.Table depTable = indexedInputTables.get(depTableId);
            ColumnPermutation dep = new ColumnPermutation();
            for (int depColumnId : ind.getDependentColumns()) {
                int columnIndex = depColumnId - depMinColumnId;
                dep.getColumnIdentifiers().add(new ColumnIdentifier(
                        depTable.name,
                        depTable.columnNames.get(columnIndex)
                ));
            }

            int refMinColumnId = ind.getReferencedColumns()[0] & ~columnBitMask;
            int refTableId = refMinColumnId | columnBitMask;
            SINDY.Table refTable = indexedInputTables.get(refTableId);
            ColumnPermutation ref = new ColumnPermutation();
            for (int refColumnId : ind.getReferencedColumns()) {
                int columnIndex = refColumnId - refMinColumnId;
                ref.getColumnIdentifiers().add(new ColumnIdentifier(
                        refTable.name,
                        refTable.columnNames.get(columnIndex)
                ));
            }
            inclusionDependency = new InclusionDependency(dep, ref);
        }
        return inclusionDependency;
    }

    /**
     * Create proper table indices as required by {@link Sindy} and also retrieve table and column names.
     *
     * @param inputGenerators that should be indexed
     * @param numColumnBits   the number of column bits in the table IDs; e.g. use 16 to share bits evenly among tables and columns
     * @return the indexed table descriptions
     */
    public static Int2ObjectMap<SINDY.Table> indexTables(FileInputGenerator[] inputGenerators, int numColumnBits) {
        Int2ObjectMap<SINDY.Table> index = new Int2ObjectOpenHashMap<>();
        int bitmask = -1 >>> (Integer.SIZE - numColumnBits);
        int tableIdDelta = bitmask + 1;
        int tableId = bitmask;
        for (FileInputGenerator inputGenerator : inputGenerators) {
            try {
                RelationalInput input = inputGenerator.generateNewCopy();
                SINDY.Table table = new SINDY.Table(
                        inputGenerator.getInputFile().getAbsoluteFile().toURI().toString(),
                        input.relationName(),
                        input.columnNames()
                );
                index.put(tableId, table);
                tableId += tableIdDelta;
            } catch (InputGenerationException | AlgorithmConfigurationException e) {
                throw new RuntimeException(e);
            }
        }
        return index;
    }

    @Override
    public String getAuthors() {
        return "Sebastian Kruse";
    }

    @Override
    public String getDescription() {
        return "This inclusion dependency algorithm uses Flink to find both unary and n-ary INDs.";
    }

    @Override
    public void setFileInputConfigurationValue(String identifier, FileInputGenerator... values) throws AlgorithmConfigurationException {
        if ("inputFiles".equalsIgnoreCase(identifier)) {
            this.fileInputGenerators = values;
        } else {
            throw new AlgorithmConfigurationException("Unknown file input configuration.");
        }
    }

    @Override
    public void setIntegerConfigurationValue(String identifier, Integer... values) throws AlgorithmConfigurationException {
        this.getPropertyLedger().configure(this, identifier, (Object[]) values);
    }

    @Override
    public void setStringConfigurationValue(String identifier, String... values) throws AlgorithmConfigurationException {
        this.getPropertyLedger().configure(this, identifier, (Object[]) values);
    }

    private static final class Table {

        final String url, name;
        final List<String> columnNames;

        public Table(String url, String name, List<String> columnNames) {
            this.url = url;
            this.name = name;
            this.columnNames = columnNames;
        }
    }
}
