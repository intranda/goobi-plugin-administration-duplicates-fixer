package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import org.goobi.beans.Process;
import org.goobi.managedbeans.ProcessBean;
import org.goobi.production.enums.PluginType;
import org.goobi.production.flow.statistics.hibernate.FilterHelper;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;
import org.goobi.production.plugin.interfaces.IPushPlugin;
import org.omnifaces.cdi.PushContext;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;

@PluginImplementation
@Log4j2
public class DuplicatesFixerAdministrationPlugin implements IAdministrationPlugin, IPushPlugin {

    @Getter
    private String title = "intranda_administration_duplicates_fixer";

    @Getter @Setter
    private int limit = 10;
    @Getter
    private int resultTotal = 0;
    @Getter
    private int resultProcessed = 0;
    @Getter
    private boolean run = false;
    @Getter
    @Setter
    private String filter;
    @Getter
    private List<DuplicatesFixerResult> resultsLimited = new ArrayList<>();
    private List<DuplicatesFixerResult> results = new ArrayList<>();
    private PushContext pusher;

    @Override
    public PluginType getType() {
        return PluginType.Administration;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_administration_duplicates_fixer.xhtml";
    }

    /**
     * Constructor
     */
    public DuplicatesFixerAdministrationPlugin() {
        log.info("Sample admnistration plugin started");
        filter = ConfigPlugins.getPluginConfig(title).getString("filter", "");
    }

    /**
     * action method to run through all processes matching the filter
     */
    public void execute() {
        run = true;
        // filter the list of all processes that should be affected
        String query = FilterHelper.criteriaBuilder(filter, false, null, null, null, true, false);
        List<Integer> tempProcesses = ProcessManager.getIdsForFilter(query);
        resultTotal = tempProcesses.size();
        resultProcessed = 0;
        results = new ArrayList<>();
        resultsLimited = new ArrayList<>();
        Runnable runnable = () -> {
            try {
                long lastPush = System.currentTimeMillis();
                List <String> existing = new ArrayList<>();
                for (Integer processId : tempProcesses) {
                    Process process = ProcessManager.getProcessById(processId);
                    //					Thread.sleep(1000);
                    if (!run) {
                        break;
                    }
                    DuplicatesFixerResult r = new DuplicatesFixerResult();
                    r.setTitle(process.getTitel());
                    r.setId(process.getId());
                    try {
                        // Prefs prefs = process.getRegelsatz().getPreferences();
                        Fileformat ff = process.readMetadataFile();
                        // load topstruct
                        DocStruct topstruct = ff.getDigitalDocument().getLogicalDocStruct();
                        if (topstruct.getType().isAnchor()) {
                            topstruct = topstruct.getAllChildren().get(0);
                        }

                        // read catalogue identifier
                        MetadataType idType = process.getRegelsatz().getPreferences().getMetadataTypeByName("CatalogIDDigital");

                        List<? extends Metadata> list = topstruct.getAllMetadataByType(idType);
                        if (list.size() > 0) {
                            Metadata md = list.get(0);
                            if (existing.contains(md.getValue())) {
                                int suffix = 1;
                                while (existing.contains(md.getValue() + "_" + suffix)) {
                                    suffix++;
                                }
                                String newID = md.getValue() + "_" + suffix;
                                md.setValue(newID);
                                process.setTitel(process.getTitel() + "_" + suffix);
                                process.writeMetadataFile(ff);
                                ProcessManager.saveProcess(process);
                                existing.add(newID);
                                r.setMessage("Changed to new Identifier: " + newID + " and added suffix '_" + suffix + "' to process title.");
                                r.setStatus("FIXED");
                            } else {
                                existing.add(md.getValue());
                                r.setStatus("OK");
                            }

                        }

                    } catch (Exception e) {
                        r.setStatus("ERROR");
                        r.setMessage(e.getMessage());
                    }
                    results.add(0, r);
                    if (results.size()>limit) {
                        resultsLimited = new ArrayList<>(results.subList(0, limit));
                    } else {
                        resultsLimited = new ArrayList<>(results);
                    }
                    resultProcessed++;
                    if (pusher != null && System.currentTimeMillis() - lastPush > 1000) {
                        lastPush = System.currentTimeMillis();
                        pusher.send("update");
                    }
                }
                run = false;
                Thread.sleep(200);
                if (pusher != null) {
                    pusher.send("update");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        };
        new Thread(runnable).start();
    }

    /**
     * show the result inside of the process list
     * 
     * @return
     */
    public String showInProcessList(String limit) {
        String search = "\"id:";
        for (DuplicatesFixerResult r : results) {
            if (limit.isEmpty() || limit.equals(r.getStatus())) {
                search += r.getId() + " ";
            }
        }
        search += "\"";
        ProcessBean processBean = Helper.getBeanByClass(ProcessBean.class);
        processBean.setFilter( search);
        processBean.setModusAnzeige("aktuell");
        return processBean.FilterAlleStart();
    }
    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;
    }
    /**
     * get the progress in percent to render a progress bar
     * @return progress as percentage
     */
    public int getProgress() {
        return 100 * resultProcessed / resultTotal;
    }
    /**
     * stop further processing
     */
    public void cancel() {
        run = false;
    }
}
