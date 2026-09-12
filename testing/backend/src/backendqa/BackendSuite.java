package backendqa;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.remrin.server.*;
import com.remrin.server.config.MachineConfig;
import com.remrin.server.config.MachineConfig.*;
import com.remrin.server.net.MachinePayloads;
import com.remrin.rules.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import io.netty.buffer.Unpooled;

public final class BackendSuite implements ModInitializer {
   private static final Gson GSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
   private static final String[] GROUPS={"bootstrap","transport","crud","permissions","validation","locks","detection","cache-disk","scheduler","modes","fakeplayers","sync","persistence","rules"};
   private final Deque<Case> cases=new ArrayDeque<>();
   private final List<Result> results=new ArrayList<>();
   private final List<Mark> marks=new ArrayList<>();
   private MinecraftServer server;
   private ServerPlayer admin,editor,guest;
   private MachineData current;
   private Case active;
   private int clock,bootstrap,serial,assertions,request=10;
   private boolean finished;
   private int delayedJoinTick=-1,lateJoinedTick=-1;
   private com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> nativePlayerCommands;
   private String phase;
   private Path run;
   private record Mark(String text,int tick){}
   private record Result(String group,String name,String status,int ticks,String error){}
   @FunctionalInterface private interface Task {void run()throws Exception;}
   private static final class Case {
      String group,name; Task start,verify; BooleanSupplier ready; int startTick,serverStartTick,timeout;
      Case(String g,String n,Task s,BooleanSupplier r,Task v,int timeout){group=g;name=n;start=s;ready=r;verify=v;this.timeout=timeout;}
   }
   public void onInitialize(){
      run=FabricLoader.getInstance().getGameDir(); phase=System.getProperty("commandgui.backendPhase","suite");
      if(!Boolean.getBoolean("commandgui.backendTest")||!Files.isRegularFile(run.resolve(".backend-test-isolated")))
         throw new IllegalStateException("Backend test mod may only run in an isolated script-created directory");
      CommandRegistrationCallback.EVENT.register((dispatcher,ctx,selection)->{
         dispatcher.register(Commands.literal("qabackend").then(Commands.argument("mark",StringArgumentType.greedyString()).executes(c->{
            String mark=StringArgumentType.getString(c,"mark"); marks.add(new Mark(mark,c.getSource().getServer().getTickCount()));
            if(mark.startsWith("power:")){String[] p=mark.split(":");MachineData m=MachineConfig.getMachine(p[1]);if(m!=null)power(m.detection,p[2].equals("on"));}
            return 1;
         })));
         dispatcher.register(Commands.literal("qarestricted").requires(s->Commands.LEVEL_OWNERS.check(s.permissions())).executes(c->{marks.add(new Mark("restricted",server.getTickCount()));return 1;}));
         dispatcher.register(Commands.literal("qanoop").executes(c->0));
         dispatcher.register(Commands.literal("qacontext").executes(c->{marks.add(new Mark("context:"+(int)c.getSource().getPosition().x,server.getTickCount()));return 1;}));
         // Controlled asynchronous spawn fixtures: acceptance now, real Carpet entity creation later.
         dispatcher.register(Commands.literal("player").then(Commands.literal("QAB_Never").then(Commands.literal("spawn").executes(c->1))));
         dispatcher.register(Commands.literal("player").then(Commands.literal("QAB_Late")
            .then(Commands.literal("spawn").executes(c->{delayedJoinTick=clock+60;return 1;}))
            .then(Commands.literal("attack").then(Commands.literal("continuous").executes(c->nativePlayerCommands.execute("player QAB_Late attack continuous",c.getSource()))))));
      });
      ServerLifecycleEvents.SERVER_STARTED.register(s->{server=s;bootstrap=1;
         nativePlayerCommands=new com.mojang.brigadier.CommandDispatcher<>();
         var original=s.getCommands().getDispatcher().getRoot().getChild("player");
         var copy=original.createBuilder().build();
         for(var child:original.getChildren())if(!child.getName().startsWith("QAB_"))copy.addChild(child);
         nativePlayerCommands.getRoot().addChild(copy);
      });
      ServerTickEvents.END_SERVER_TICK.register(s->{if(server!=null&&!finished)tick();});
   }
   private void tick(){
      clock++;
      try{
         if(delayedJoinTick==clock){
            server.services().nameToIdCache().add(NameAndId.createOffline("QAB_Late"));
            var method=Arrays.stream(Class.forName("carpet.patches.EntityPlayerMPFake").getMethods()).filter(m->m.getName().equals("createFake")).findFirst().orElseThrow();
            method.invoke(null,"QAB_Late",server,new net.minecraft.world.phys.Vec3(0,80,0),0d,0d,server.overworld().dimension(),net.minecraft.world.level.GameType.CREATIVE,false);
         }
         if(bootstrap==1){
            if(phase.equals("restart")){registerRestart();bootstrap=3;return;}
            server.services().nameToIdCache().resolveOfflineUsers(true);
            for(String name:List.of("QAB_Admin","QAB_Editor","QAB_Guest","QAB_Bot","QAB_Left","QAB_Low","QAB_Chain","QAB_InterOn","QAB_InterOff","QAB_Denied")){
               server.services().nameToIdCache().add(NameAndId.createOffline(name));
               command("player "+name+" spawn at 0 80 0");
            }
            bootstrap=2;return;
         }
         if(bootstrap==2){
            if(clock>1200)throw new IllegalStateException("Carpet fake-player fixtures did not spawn");
            admin=server.getPlayerList().getPlayerByName("QAB_Admin");editor=server.getPlayerList().getPlayerByName("QAB_Editor");guest=server.getPlayerList().getPlayerByName("QAB_Guest");
            if(admin==null||editor==null||guest==null||server.getPlayerList().getPlayerByName("QAB_Bot")==null)return;
            permission(admin,4);permission(editor,0);permission(guest,0); MachineConfig.addEditorWhitelist("QAB_Editor");
            command("tick rate 100");registerAll();bootstrap=3;return;
         }
         if(active==null){
            if(cases.isEmpty()){finish();return;}
            active=cases.removeFirst();active.startTick=clock;active.serverStartTick=server.getTickCount();
            for(MachineData m:MachineConfig.getMachines()){MachineScheduler.invalidate(m.id);MachineModeChain.invalidate(m.id);MachineScheduler.clearSwitchTicks(m.id);}
            Hooks.clear();marks.clear();MachineScheduler.clearExecTrace();
            active.start.run();
         }
         if(active.ready.getAsBoolean()){active.verify.run();record(null);}
         else if(clock-active.startTick>active.timeout)record(new AssertionError("Timed out after "+active.timeout+" ticks; "+MachineScheduler.dumpRuntimeState()));
      }catch(Throwable e){if(active!=null)record(e);else{results.add(new Result("bootstrap","fixture setup","FAIL",clock,stack(e)));finish();}}
   }
   private void record(Throwable error){
      Result r=new Result(active.group,active.name,error==null?"PASS":"FAIL",clock-active.startTick,error==null?"":stack(error));results.add(r);
      System.out.println("BACKEND_TEST "+r.status+" "+r.group+" / "+r.name+(error==null?"":" : "+error));active=null;write(false);
   }
   private String stack(Throwable error){var sw=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(sw));return sw.toString();}
   private void test(String group,String name,Task task){cases.add(new Case(group,name,task,()->true,()->{},1));}
   private void async(String group,String name,Task start,BooleanSupplier ready,Task verify,int timeout){cases.add(new Case(group,name,start,ready,verify,timeout));}
   private void check(boolean yes,String detail){assertions++;if(!yes)throw new AssertionError(detail);}
   private void eq(Object actual,Object expected,String detail){check(Objects.equals(actual,expected),detail+" expected="+expected+" actual="+actual);}
   private void command(String command){server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);}
   private void permission(ServerPlayer p,int level){if(level==0)server.getPlayerList().deop(new NameAndId(p.getGameProfile()));else server.getPlayerList().op(new NameAndId(p.getGameProfile()),Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level))),Optional.of(true));}
   private JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
   private JsonObject action(String type,String id){JsonObject a=new JsonObject();a.addProperty("type",type);if(id!=null)a.addProperty("machineId",id);return a;}
   private void action(ServerPlayer p,JsonObject a){receive(p,new MachinePayloads.ActionPayload(a.toString()));}
   @SuppressWarnings({"rawtypes","unchecked"}) private void receive(ServerPlayer p,CustomPacketPayload payload){
      var handler=(ServerPlayNetworking.PlayPayloadHandler)Hooks.receivers.get(payload.type().id().toString());
      check(handler!=null,"registered receiver "+payload.type().id());
      handler.receive(payload,new ServerPlayNetworking.Context(){public MinecraftServer server(){return server;}public ServerPlayer player(){return p;}public PacketSender responseSender(){return ServerPlayNetworking.getSender(p);}});
   }
   private void editLock(ServerPlayer p,MachineData m,boolean open){var a=action("editSession",m.id);a.addProperty("open",open);action(p,a);}
   private void saveAction(ServerPlayer p,String type,MachineData m,int revision){var a=action(type,null);a.add("machine",GSON.toJsonTree(m));a.addProperty("baseRevision",revision);action(p,a);}
   private MachineData copy(MachineData m){return GSON.fromJson(GSON.toJson(m),MachineData.class);}
   private DetectionData detection(int x,int y,int z){
      DetectionData d=new DetectionData();d.enabled=true;d.x=x;d.y=y;d.z=z;d.blockId="minecraft:lever";d.property="powered";d.onValues.add("powered=true");d.offValues.add("powered=false");
      var state=Blocks.LEVER.defaultBlockState();state.getValues().forEach(v->{if(!v.property().getName().equals("powered"))d.ignoreValues.add(v.property().getName()+"="+v.valueName());});return d;
   }
   private void power(DetectionData d,boolean on){var pos=new BlockPos(d.x,d.y,d.z);server.overworld().getChunkAt(pos);server.overworld().setBlock(pos.south(),Blocks.STONE.defaultBlockState(),2);server.overworld().setBlock(pos,Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED,on),2);}
   private Step step(String...commands){Step s=new Step();s.commands=new ArrayList<>(List.of(commands));return s;}
   private Timeline timeline(String...commands){Timeline t=new Timeline();t.steps.add(step(commands));return t;}
   private MachineData fixture(){
      MachineData m=new MachineData();m.id="qa_"+(++serial);m.name="测试机器 "+serial;m.category="测试分类";m.bots.add("QAB_Bot");m.switchInterval=0;
      m.detection=detection(32+serial%16,80,32+serial/16);power(m.detection,false);
      m.onTimeline=timeline("player {bot} spawn at 0 80 0","qabackend power:"+m.id+":on");
      m.offTimeline=timeline("qabackend power:"+m.id+":off");return m;
   }
   private MachineData stored(){MachineData m=fixture();check(MachineConfig.addMachine(m),"fixture stored");return m;}
   private void message(ServerPlayer p,String contains){check(Hooks.messages(p).contains(contains),"expected message '"+contains+"', got "+Hooks.messages(p));}
   private JsonObject sync(ServerPlayer p){Hooks.packets.remove(p.getGameProfile().name());MachineManager.syncTo(p);return json(Hooks.packets(p,MachinePayloads.SyncPayload.class).getLast().json());}
   private Object field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
   private Object call(Class<?> type,String name,Class<?>[] types,Object...args)throws Exception{var m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
   private void registerAll(){bootstrapTests();crudTests();permissionTests();validationTests();lockTests();detectionTests();schedulerTests();modeTests();fakeTests();syncTests();persistenceTests();ruleTests();interruptionTests();}
   private void bootstrapTests(){
      test("bootstrap","entrypoints and commands",()->{
         check(FabricLoader.getInstance().isModLoaded("command-gui"),"combined mod loaded");check(!FabricLoader.getInstance().isModLoaded("command-gui-server"),"no legacy server mod");
         check(MachineMod.getCurrentServer()==server,"server reference initialized");
         for(String name:List.of("machineadmin","cgtest","carpet","player"))check(server.getCommands().getDispatcher().getRoot().getChild(name)!=null,"command registered before reload: "+name);
      });
      test("bootstrap","cgtest diagnostic command adapters",()->{var m=stored();var dispatcher=server.getCommands().getDispatcher();for(String suffix:List.of("machine list","lock list","timeline","sched","traceclear","edit QAB_Admin "+m.id+" open","edit QAB_Admin "+m.id+" close","run QAB_Admin "+m.id+" off","stop "+m.id,"action QAB_Admin {\"type\":\"requestSync\"}")){check(dispatcher.execute("cgtest "+suffix,server.createCommandSourceStack())>0,"diagnostic command "+suffix);}check(!MachineScheduler.isRunning(m.id),"diagnostic stop");action(admin,action("machineStatesUnsubscribe",null));dispatcher.execute("cgtest delete QAB_Admin "+m.id,server.createCommandSourceStack());check(MachineConfig.getMachine(m.id)==null,"diagnostic delete");});
      test("transport","all payload codecs and actual registered handlers",()->{
         roundtrip(MachinePayloads.ActionPayload.CODEC,new MachinePayloads.ActionPayload("{\"中文\":true}"));
         roundtrip(MachinePayloads.SyncPayload.CODEC,new MachinePayloads.SyncPayload("{\"machines\":[]}"));
         roundtrip(MachinePayloads.BlockQueryResultPayload.CODEC,new MachinePayloads.BlockQueryResultPayload("{}"));
         roundtrip(MachinePayloads.FakePlayerStatesPayload.CODEC,new MachinePayloads.FakePlayerStatesPayload("{}"));
         roundtrip(RulePayloads.Query.CODEC,new RulePayloads.Query(42));roundtrip(RulePayloads.Apply.CODEC,new RulePayloads.Apply(43,"[]"));roundtrip(RulePayloads.Snapshot.CODEC,new RulePayloads.Snapshot(44,"{}"));
         for(var type:List.of(MachinePayloads.ActionPayload.TYPE,RulePayloads.Query.TYPE,RulePayloads.Apply.TYPE))check(Hooks.receivers.containsKey(type.id().toString()),"receiver "+type);
         int count=MachineConfig.getMachines().size();receive(guest,new MachinePayloads.ActionPayload("not JSON"));receive(guest,new MachinePayloads.ActionPayload("{}"));receive(guest,new MachinePayloads.ActionPayload("{\"type\":\"invalid\"}"));eq(MachineConfig.getMachines().size(),count,"malformed action cannot mutate");
      });
   }
   private <T>void roundtrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),server.registryAccess());try{codec.encode(buf,value);eq(codec.decode(buf),value,"codec roundtrip");eq(buf.readableBytes(),0,"codec consumed packet");}finally{buf.release();}}
   private void crudTests(){
      test("crud","add and unique id",()->{var m=fixture();saveAction(admin,"add",m,-1);check(MachineConfig.getMachine(m.id)!=null,"added");eq(MachineConfig.getMachine(m.id).revision,1,"initial revision");int count=MachineConfig.getMachines().size();saveAction(admin,"add",m,-1);eq(MachineConfig.getMachines().size(),count+1,"duplicate id gets unique suffix");});
      test("crud","edit requires own lock and optimistic revision",()->{var m=stored();var edit=copy(m);edit.name="已修改";saveAction(admin,"edit",edit,m.revision);eq(MachineConfig.getMachine(m.id).name,m.name,"save without lock denied");editLock(admin,m,true);saveAction(admin,"edit",edit,9999);eq(MachineConfig.getMachine(m.id).revision,1,"stale revision denied");saveAction(admin,"edit",edit,m.revision);eq(MachineConfig.getMachine(m.id).name,"已修改","edited");eq(MachineConfig.getMachine(m.id).revision,2,"revision increments");check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Editor")==null,"save releases lock");});
      test("crud","delete and clear category permissions",()->{var m=stored();action(editor,action("delete",m.id));check(MachineConfig.getMachine(m.id)!=null,"whitelisted editor cannot delete");var a=action("clearCategory",null);a.addProperty("categoryId",m.category);action(guest,a);eq(m.category,"测试分类","guest cannot clear");action(admin,a);eq(m.category,"","category cleared");action(admin,action("delete",m.id));check(MachineConfig.getMachine(m.id)==null,"deleted");action(admin,action("delete","missing"));message(admin,"不存在");});
   }
   private void permissionTests(){
      test("permissions","whitelist commands and restricted editor fields",()->{
         var m=fixture();saveAction(guest,"add",m,-1);check(MachineConfig.getMachine(m.id)==null,"guest cannot add");
         command("machineadmin add QAB_Editor");eq(MachineConfig.getEditorWhitelist().stream().filter(n->n.equalsIgnoreCase("QAB_Editor")).count(),1L,"case-insensitive dedupe");
         m.permissionLevel=4;m.bannedPlayers.add("QAB_Guest");saveAction(editor,"add",m,-1);var saved=MachineConfig.getMachine(m.id);eq(saved.permissionLevel,2,"editor cannot grant owner threshold");check(saved.bannedPlayers.isEmpty(),"editor cannot set ban list");
         saved.permissionLevel=4;saved.bannedPlayers.add("QAB_Guest");editLock(editor,saved,true);var edited=copy(saved);edited.permissionLevel=0;edited.bannedPlayers.clear();saveAction(editor,"edit",edited,saved.revision);eq(MachineConfig.getMachine(m.id).permissionLevel,4,"restricted fields preserved on edit");eq(MachineConfig.getMachine(m.id).bannedPlayers,List.of("QAB_Guest"),"ban list preserved");
         command("machineadmin list");command("machineadmin remove qab_editor");check(!MachineConfig.getEditorWhitelist().contains("QAB_Editor"),"remove ignores case");command("machineadmin add QAB_Editor");
         try{server.getCommands().getDispatcher().execute("machineadmin add exploit",guest.createCommandSourceStack());}catch(Exception expected){}
         check(!MachineConfig.getEditorWhitelist().contains("exploit"),"guest admin command denied");
      });
      test("permissions","machine permission matrix and ban override",()->{
         var m=stored();for(int playerLevel=0;playerLevel<=4;playerLevel++){permission(guest,playerLevel);for(int required=0;required<=4;required++){m.permissionLevel=required;boolean expected=required<=1||playerLevel>=(required==2?1:required);eq(call(MachineManager.class,"canToggle",new Class<?>[]{ServerPlayer.class,MachineData.class},guest,m),expected,"permission matrix "+playerLevel+"/"+required);}}
         m.bannedPlayers.add("QAB_Guest");eq(call(MachineManager.class,"canToggle",new Class<?>[]{ServerPlayer.class,MachineData.class},guest,m),false,"ban applies to OP too");permission(guest,0);
      });
   }
   private void validationTests(){
      Map<String,Consumer<MachineData>> invalid=new LinkedHashMap<>();
      invalid.put("empty id",m->m.id="");invalid.put("empty name",m->m.name="");invalid.put("long category",m->m.category="x".repeat(31));invalid.put("no bots",m->m.bots.clear());invalid.put("empty bot",m->m.bots.set(0,""));
      invalid.put("permission low",m->m.permissionLevel=-1);invalid.put("permission high",m->m.permissionLevel=5);invalid.put("switch interval",m->m.switchInterval=1201);invalid.put("start interval",m->m.modeInterval=12001);invalid.put("stop interval",m->m.stopModeInterval=-1);
      invalid.put("null timeline",m->m.onTimeline=null);invalid.put("empty timeline",m->m.onTimeline.steps.clear());invalid.put("null steps",m->m.offTimeline.steps=null);invalid.put("loop lower bound",m->m.onTimeline.loopCount=-2);
      invalid.put("spawn must be first",m->m.onTimeline.steps.getFirst().commands.set(0,"player {bot} attack"));invalid.put("bad bot index",m->m.onTimeline.steps.getFirst().bot=1);invalid.put("null step",m->m.onTimeline.steps.add(null));invalid.put("empty commands",m->m.onTimeline.steps.getFirst().commands.clear());invalid.put("command delay",m->m.onTimeline.steps.getFirst().commandDelay=0);
      invalid.put("delay bound",m->{Step s=new Step();s.kind="delay";s.delay=72001;m.onTimeline.steps.add(s);});
      invalid.put("detection air",m->m.detection.blockId="air");invalid.put("detection empty dimension",m->m.detection.dimension="");invalid.put("detection empty property",m->m.detection.property="");invalid.put("detection empty on",m->m.detection.onValues.clear());invalid.put("detection overlap",m->m.detection.offValues.add("powered=true"));
      invalid.put("duplicate mode",m->{var mode=mode(m,"a",false);m.modes.add(mode);m.modes.add(mode);});
      invalid.forEach((name,mutate)->test("validation",name,()->{var m=fixture();mutate.accept(m);int before=MachineConfig.getMachines().size();saveAction(admin,"add",m,-1);eq(MachineConfig.getMachines().size(),before,"invalid config rejected");message(admin,"无效");}));
      for(String root:List.of("op","deop","stop","save-all","save-off","save-on","ban","ban-ip","pardon","pardon-ip","kick","whitelist","seed","kill","gamemode","give","effect","execute","function","run","forceload","datapack","reload","publish","debug","perf","tick","jfr","msg"))
         test("validation","blocked script command "+root,()->{var m=fixture();m.onTimeline.steps.getFirst().commands.add("/"+root+" QAB_Guest");int before=MachineConfig.getMachines().size();saveAction(admin,"add",m,-1);eq(MachineConfig.getMachines().size(),before,"blocked root rejected");});
   }
   private void lockTests(){
      test("locks","mutual exclusion and owner release",()->{var m=stored();editLock(editor,m,true);editLock(admin,m,true);check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")!=null,"editor retains lock");editLock(admin,m,false);check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")!=null,"other cannot unlock");action(admin,action("toggle",m.id));check(!MachineScheduler.isRunning(m.id),"lock blocks toggle");action(admin,action("delete",m.id));check(MachineConfig.getMachine(m.id)!=null,"lock blocks deletion");editLock(editor,m,false);check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")==null,"owner releases");editLock(admin,m,true);action(admin,action("delete",m.id));check(MachineConfig.getMachine(m.id)==null,"owner may delete while editing");});
      test("locks","disconnect and stale offline lock cleanup",()->{var m=stored();editLock(editor,m,true);MachineManager.onPlayerDisconnect("QAB_Editor");check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")==null,"disconnect releases");Class<?> lock=Class.forName("com.remrin.server.MachineManager$EditLock");var ctor=lock.getDeclaredConstructor(String.class,long.class);ctor.setAccessible(true);var locks=(Map)field(MachineManager.class,"editLocks");locks.put(m.id,ctor.newInstance("QAB_Editor",System.currentTimeMillis()-3600001));MachineManager.debugLocksDump();check(!locks.containsKey(m.id),"TTL expires");locks.put(m.id,ctor.newInstance("QAB_Offline",System.currentTimeMillis()));MachineManager.debugLocksDump();check(!locks.containsKey(m.id),"offline lock expires");});
      test("locks","unprivileged guest cannot acquire edit lock",()->{var m=stored();try{editLock(guest,m,true);check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")==null,"guest must not block authorized editors");}finally{editLock(guest,m,false);}});
      test("locks","running transition refuses editor",()->{var m=stored();MachineScheduler.start(m,m.onTimeline,"QAB_Admin",false);editLock(editor,m,true);check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")==null,"no lock during machine transition");message(editor,"无法编辑");});
   }
   private void detectionTests(){
      test("detection","disabled on off abnormal and properties",()->{
         var m=stored();eq(MachineDetector.evaluateDetection(null,server,true).state(),MachineDetector.MachineState.DISABLED,"missing detection");
         eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.OFF,"off");power(m.detection,true);eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.ON,"on");
         m.detection.blockId="stone";eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.ABNORMAL,"wrong block");m.detection.blockId="lever";
         m.detection.ignoreValues.clear();eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.ABNORMAL,"unconfigured property");
         m.detection=detection(m.detection.x,m.detection.y,m.detection.z);m.detection.offValues.add("facing=north");m.detection.ignoreValues.remove("facing=north");eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.ABNORMAL,"conflicting on/off");
         m.detection.dimension="minecraft:no_such_dimension";eq(MachineDetector.evaluate(m,server),MachineDetector.MachineState.ABNORMAL,"missing dimension");eq(MachineDetector.normalizeId("lever"),"minecraft:lever","namespace normalization");
      });
      test("detection","block query response and detection refresh",()->{
         var m=stored();var a=action("queryBlock",null);a.addProperty("dimension",m.detection.dimension);a.addProperty("x",m.detection.x);a.addProperty("y",m.detection.y);a.addProperty("z",m.detection.z);a.addProperty("token",91827L);action(admin,a);
         var packet=Hooks.packets(admin,MachinePayloads.BlockQueryResultPayload.class).getLast();var data=json(packet.json());check(data.get("found").getAsBoolean(),"query found");eq(data.get("token").getAsLong(),91827L,"token correlation");eq(data.get("blockId").getAsString(),"minecraft:lever","query block id");check(data.getAsJsonObject("properties").getAsJsonArray("powered").size()==2,"all property options");eq(data.getAsJsonObject("current").get("powered").getAsString(),"false","current properties");
         a.addProperty("dimension","minecraft:missing");action(admin,a);check(!json(Hooks.packets(admin,MachinePayloads.BlockQueryResultPayload.class).getLast().json()).get("found").getAsBoolean(),"missing dimension query");action(admin,action("refreshDetection",m.id));message(admin,"关机");
      });
      test("cache-disk","positive negative cache invalidation and TTL",()->{
         var m=stored();var d=m.detection;MachineBlockCache.rebuild();MachineBlockCache.prime(server);check(MachineBlockCache.get(d.dimension,d.x,d.y,d.z)!=null,"prime snapshots watched position");
         MachineBlockCache.putDiskResult(d.dimension,d.x,d.y,d.z,null);check(MachineBlockCache.hasFreshDisk(d.dimension,d.x,d.y,d.z),"negative result cached");check(MachineBlockCache.getDisk(d.dimension,d.x,d.y,d.z)==null,"negative cache remains null");
         MachineBlockCache.invalidate(m);check(!MachineBlockCache.hasFreshDisk(d.dimension,d.x,d.y,d.z),"disk invalidated");check(MachineBlockCache.get(d.dimension,d.x,d.y,d.z)==null,"snapshot invalidated");
         var entry=new BlockStateFileReader.BlockStateEntry("minecraft:lever",Map.of("powered","true"));MachineBlockCache.putDiskResult(d.dimension,d.x,d.y,d.z,entry);eq(MachineBlockCache.getDisk(d.dimension,d.x,d.y,d.z),entry,"positive cache");
         var cache=(Map<String,Map<BlockPos,Object>>)field(MachineBlockCache.class,"diskCache");Class<?> type=Class.forName("com.remrin.server.MachineBlockCache$DiskEntry");var ctor=type.getDeclaredConstructor(BlockStateFileReader.BlockStateEntry.class,long.class);ctor.setAccessible(true);cache.get(d.dimension).put(new BlockPos(d.x,d.y,d.z),ctor.newInstance(entry,System.currentTimeMillis()-30001));check(!MachineBlockCache.hasFreshDisk(d.dimension,d.x,d.y,d.z),"expired cache not reused");
      });
      test("cache-disk","real region files asymmetric and negative coordinates",()->{
         for(int[] p:new int[][]{{49,80,113},{-49,80,-113}}){var d=detection(p[0],p[1],p[2]);power(d,true);}
         server.saveEverything(false,true,true);
         for(int[] p:new int[][]{{49,80,113},{-49,80,-113}}){var entry=BlockStateFileReader.read(server,"minecraft:overworld",p[0],p[1],p[2]);check(entry!=null,"disk block at "+Arrays.toString(p));eq(entry.blockId(),"minecraft:lever","not transposed region coordinates");eq(entry.properties().get("powered"),"true","disk properties");}
         check(BlockStateFileReader.read(server,"minecraft:overworld",10000000,80,10000000)==null,"ungenerated disk chunk");
         var m=stored();MachineBlockCache.rebuild();MachineBlockCache.onChunkUnload(server.overworld(),server.overworld().getChunkAt(new BlockPos(m.detection.x,m.detection.y,m.detection.z)));check(MachineBlockCache.get(m.detection.dimension,m.detection.x,m.detection.y,m.detection.z)!=null,"chunk unload snapshot fallback");
      });
   }
   private void schedulerTests(){
      async("scheduler","toggle starts real spawn then on timeline",()->{current=stored();action(admin,action("toggle",current.id));check(MachineScheduler.isRunning(current.id),"start registered; feedback="+Hooks.messages(admin));},()->!MachineScheduler.isRunning(current.id),()->{eq(MachineDetector.evaluate(current,server),MachineDetector.MachineState.ON,"on transition effect");check(MachineScheduler.dumpExecTrace().stream().anyMatch(s->s.contains("player QAB_Bot spawn")),"bot placeholder resolved");},400);
      async("scheduler","literal spawn executes complete boot",()->{current=stored();current.onTimeline.steps.getFirst().commands.set(0,"player QAB_Bot spawn at 0 80 0");action(admin,action("toggle",current.id));check(MachineScheduler.isRunning(current.id),"literal start registered; "+Hooks.messages(admin));},()->!MachineScheduler.isRunning(current.id),()->eq(MachineDetector.evaluate(current,server),MachineDetector.MachineState.ON,"literal on effect"),400);
      async("scheduler","toggle off and cooldown",()->{current=stored();power(current.detection,true);current.switchInterval=40;action(admin,action("toggle",current.id));check(MachineScheduler.isShuttingDown(current.id),"off transition");action(admin,action("toggle",current.id));message(admin,"正在");},()->!MachineScheduler.isRunning(current.id),()->{eq(MachineDetector.evaluate(current,server),MachineDetector.MachineState.OFF,"off effect");check(MachineScheduler.switchLockRemaining(current.id,server.getTickCount())>0,"cooldown remaining");action(admin,action("toggle",current.id));check(!MachineScheduler.isRunning(current.id),"cooldown blocks new start");MachineScheduler.clearSwitchTicks(current.id);eq(MachineScheduler.switchLockRemaining(current.id,server.getTickCount()),0,"clear cooldown");},200);
      test("scheduler","toggle denies abnormal disabled banned and bad spawn",()->{var m=stored();m.detection.enabled=false;action(admin,action("toggle",m.id));check(!MachineScheduler.isRunning(m.id),"disabled denied");m.detection.enabled=true;m.detection.blockId="stone";action(admin,action("toggle",m.id));check(!MachineScheduler.isRunning(m.id),"abnormal denied");m.detection.blockId="lever";m.bannedPlayers.add("QAB_Admin");action(admin,action("toggle",m.id));check(!MachineScheduler.isRunning(m.id),"banned denied");m.bannedPlayers.clear();m.onTimeline=timeline("qabackend forbidden");action(admin,action("toggle",m.id));check(!MachineScheduler.isRunning(m.id),"first spawn required");});
      async("scheduler","step delay command delay and player substitution",()->{current=stored();Timeline t=timeline("qabackend first:{player}","qabackend second");t.steps.getFirst().commandDelay=5;Step delay=new Step();delay.kind="delay";delay.delay=4;t.steps.add(0,delay);MachineScheduler.start(current,t,"QAB_Admin",false);},()->!MachineScheduler.isRunning(current.id),()->{eq(marks.size(),2,"both commands execute");eq(marks.getFirst().text,"first:QAB_Admin","player substituted");check(marks.get(1).tick-marks.getFirst().tick>=5,"command delay honored");check(marks.getFirst().tick>=active.serverStartTick+4,"initial delay honored");},100);
      async("scheduler","finite loop count",()->{current=stored();Timeline t=timeline("qabackend loop");t.loopCount=3;MachineScheduler.start(current,t,"QAB_Admin",false);},()->!MachineScheduler.isRunning(current.id),()->eq(marks.size(),3,"exactly three loops"),100);
      async("scheduler","infinite loop cancellation",()->{current=stored();Timeline t=timeline("qabackend repeat");t.loopCount=-1;MachineScheduler.start(current,t,"QAB_Admin",false);},()->marks.size()>=3,()->{MachineScheduler.stop(current.id);check(!MachineScheduler.isRunning(current.id),"stop infinite loop");},100);
      for(String failing:List.of("qarestricted","qanoop","not_a_real_command","player QAB_Missing attack continuous"))async("scheduler","failure abort: "+failing,()->{current=stored();MachineScheduler.start(current,timeline(failing,"qabackend MUST_NOT_RUN"),"QAB_Guest",false);},()->!MachineScheduler.isRunning(current.id),()->{check(marks.isEmpty(),"failure stops later commands");check(!MachineScheduler.dumpExecTrace().isEmpty(),"failure recorded");},100);
      async("scheduler","spawn await timeout does not fake completion",()->{current=stored();current.bots.set(0,"QAB_Never");MachineScheduler.start(current,timeline("player QAB_Never spawn","qabackend MUST_NOT_RUN"),"QAB_Admin",false);},()->!MachineScheduler.isRunning(current.id),()->{check(marks.isEmpty(),"nothing after missing spawn");message(admin,"超时");},1000);
      async("scheduler","mode spawn timeout releases runtime and reports once",()->{current=stored();current.bots.set(0,"QAB_Never");var m=mode(current,"timeout",false);m.onTimeline=timeline("player QAB_Never spawn","qabackend MUST_NOT_RUN");current.modes.add(m);MachineScheduler.startMode(current,m,"QAB_Admin");},()->!MachineScheduler.isModeProcessRunning(current.id,"timeout"),()->{check(marks.isEmpty(),"no commands after failed mode spawn");eq(Hooks.messages.getOrDefault("QAB_Admin",List.of()).stream().filter(x->x.contains("超时")).count(),1L,"single mode timeout notification");},1000);
      test("scheduler","preflight rejects unknown incomplete and trailing commands",()->{for(String cmd:List.of("not_a_real_command","qabackend","qarestricted trailing"))check(MachineScheduler.checkCommandPermission(cmd,"QAB_Admin")!=null,"invalid preflight: "+cmd);check(MachineScheduler.checkCommandPermission("qabackend valid","QAB_Admin")==null,"valid preflight");check(marks.isEmpty(),"preflight never executes commands");});
      test("scheduler","mode cooldown fallback and permission capture",()->{var m=stored();m.switchInterval=40;var mode=mode(m,"cool",false);m.modes.add(mode);MachineScheduler.recordModeSwitchTick(m.id,mode.id,server.getTickCount());eq(MachineScheduler.modeLockRemaining(m.id,mode.id,server.getTickCount()),40,"machine fallback cooldown");mode.switchInterval=12;eq(MachineScheduler.modeLockRemaining(m.id,mode.id,server.getTickCount()),12,"per-mode cooldown");check(MachineScheduler.checkCommandPermission("qarestricted","QAB_Guest")!=null,"guest rejected");check(MachineScheduler.checkCommandPermission("qarestricted","QAB_Admin")==null,"owner allowed");check(MachineScheduler.checkCommandPermission("qarestricted","")!=null,"empty trigger rejected");});
   }
   private ModeData mode(MachineData m,String id,boolean single){ModeData mode=new ModeData();mode.id=id;mode.name=id;mode.singleSelect=single;mode.onTimeline=timeline("player {bot} spawn at 0 80 0","qabackend start:"+id);mode.offTimeline=timeline("qabackend stop:"+id);return mode;}
   private void setModes(MachineData m,String...ids){var a=action("setModes",m.id);a.add("modeIds",GSON.toJsonTree(ids));action(admin,a);}
   private void modeTests(){
      async("modes","multi-mode start order and interval",()->{current=stored();current.modes.add(mode(current,"a",false));current.modes.add(mode(current,"b",false));current.modeOrder=List.of("b","a");current.modeInterval=5;setModes(current,"a","b");check(MachineModeChain.isActive(current.id),"batch chain created");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->{eq(marks.stream().map(Mark::text).toList(),List.of("start:b","start:a"),"native mode order");check(marks.get(1).tick-marks.getFirst().tick>=5,"start interval");},400);
      async("modes","literal spawn multi-mode chain",()->{current=stored();for(String id:List.of("a","b")){var m=mode(current,id,false);m.onTimeline.steps.getFirst().commands.set(0,"player QAB_Bot spawn at 0 80 0");current.modes.add(m);}current.modeOrder=List.of("b","a");current.modeInterval=5;setModes(current,"a","b");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->eq(marks.stream().map(Mark::text).toList(),List.of("start:b","start:a"),"literal mode order"),400);
      async("modes","stop chain independent stop order",()->{current=stored();current.modes.add(mode(current,"a",false));current.modes.add(mode(current,"b",false));current.stopModeOrder=List.of("b","a");current.stopModeInterval=5;MachineModeChain.buildChains(current,List.of(),List.of("a","b"),"QAB_Admin");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->{eq(marks.stream().map(Mark::text).toList(),List.of("stop:b","stop:a"),"stop order");check(marks.get(1).tick-marks.getFirst().tick>=5,"stop interval");},200);
      async("modes","stop follows start uses start order",()->{current=stored();current.modes.add(mode(current,"a",false));current.modes.add(mode(current,"b",false));current.modeOrder=List.of("b","a");current.stopModeOrder=List.of("a","b");current.stopFollowsStart=true;current.modeInterval=5;MachineModeChain.buildChains(current,List.of(),List.of("a","b"),"QAB_Admin");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->{eq(marks.stream().map(Mark::text).toList(),List.of("stop:b","stop:a"),"follow-start stop ordering");check(marks.get(1).tick-marks.getFirst().tick>=5,"follow-start stop interval");},200);
      async("modes","single-select replacement stops before starting",()->{current=stored();var a=mode(current,"a",true);var b=mode(current,"b",true);current.modes.addAll(List.of(a,b));MachineScheduler.updateDetectedModeState(current.id,"a",MachineDetector.MachineState.ON);MachineModeChain.buildChains(current,List.of("b"),List.of(),"QAB_Admin");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->eq(marks.stream().map(Mark::text).toList(),List.of("stop:a","start:b"),"replacement ordering"),400);
      test("modes","busy states and editor lock prevent mode changes",()->{var m=stored();m.modes.add(mode(m,"a",false));editLock(editor,m,true);setModes(m,"a");check(!MachineModeChain.isActive(m.id)&&!MachineScheduler.isModeProcessRunning(m.id,"a"),"lock blocks modes");editLock(editor,m,false);MachineScheduler.startMode(m,m.modes.getFirst(),"QAB_Admin");setModes(m,"a");message(admin,"正在执行");editLock(editor,m,true);message(editor,"无法编辑");});
      async("modes","deleted machine cancels queued chain",()->{current=stored();current.modes.add(mode(current,"a",false));MachineModeChain.buildChains(current,List.of("a"),List.of(),"QAB_Admin");MachineConfig.removeMachine(current.id);},()->!MachineModeChain.isActive(current.id),()->check(marks.isEmpty(),"deleted machine never starts"),50);
      test("modes","bad and abnormal mode start rejected",()->{var m=stored();var a=mode(m,"a",true);a.detection=detection(128,80,128);a.detection.blockId="stone";m.modes.add(a);setModes(m,"a");check(!MachineScheduler.isModeProcessRunning(m.id,"a"),"abnormal mode denied");a.detection=null;a.onTimeline=timeline("qabackend BAD");setModes(m,"a");check(!MachineScheduler.isModeProcessRunning(m.id,"a"),"mode first spawn required");MachineModeChain.invalidate(m.id);MachineScheduler.invalidate(m.id);});
   }
   private void fakeTests(){
      test("fakeplayers","Carpet action state and intervals",()->{check(FakePlayerStateTracker.isSupported(),"Carpet tracker reflection resolved");command("player QAB_Bot attack continuous");command("player QAB_Bot use interval 7");command("player QAB_Bot jump continuous");command("player QAB_Bot sneak");var states=json(FakePlayerStateTracker.buildJson(server)).getAsJsonObject("players").getAsJsonObject("QAB_Bot");check(states.get("attack").getAsBoolean(),"attack continuous");check(states.get("useInterval").getAsBoolean(),"use interval");eq(states.get("useIntervalTicks").getAsInt(),7,"interval ticks");check(states.get("jump").getAsBoolean(),"jump");check(states.get("sneak").getAsBoolean(),"sneak");command("player QAB_Bot stop");states=json(FakePlayerStateTracker.buildJson(server)).getAsJsonObject("players").getAsJsonObject("QAB_Bot");check(!states.get("attack").getAsBoolean(),"stop clears action");});
      test("fakeplayers","subscription endpoints",()->{action(admin,action("fakeStatesRequest",null));check(!Hooks.packets(admin,MachinePayloads.FakePlayerStatesPayload.class).isEmpty(),"request sends fake states");check(((Set<?>)field(MachineMod.class,"fakeStateSubscribers")).contains(admin.getUUID()),"subscribed");action(admin,action("fakeStatesUnsubscribe",null));check(!((Set<?>)field(MachineMod.class,"fakeStateSubscribers")).contains(admin.getUUID()),"unsubscribed");});
   }
   private void syncTests(){
      test("sync","per-player permissions editing owner and transition",()->{var m=stored();editLock(editor,m,true);var owner=sync(admin);check(owner.get("canConfig").getAsBoolean(),"OP config permission");var edit=sync(editor);check(edit.get("canEdit").getAsBoolean()&&!edit.get("canConfig").getAsBoolean(),"whitelisted editor permissions");var unprivileged=sync(guest);check(!unprivileged.get("canEdit").getAsBoolean(),"guest cannot edit");check(owner.toString().contains("QAB_Editor"),"lock owner synced");editLock(editor,m,false);MachineScheduler.start(m,m.offTimeline,"QAB_Admin",true);check(sync(admin).toString().contains("\"transition\":\"off\""),"transition synced");});
      test("sync","subscribe unsubscribe and idle detection gate",()->{action(admin,action("requestSync",null));check(MachineMod.hasMachineStateSubscribers(),"machine subscription");check(!Hooks.packets(admin,MachinePayloads.SyncPayload.class).isEmpty(),"sync payload");action(admin,action("machineStatesUnsubscribe",null));check(!MachineMod.hasMachineStateSubscribers(),"unsubscribe");Hooks.clear();MachineManager.tickStates(server);check(Hooks.packets(admin,MachinePayloads.SyncPayload.class).isEmpty(),"idle tick does not broadcast");MachineManager.broadcastSync(server.getPlayerList());check(!Hooks.packets(admin,MachinePayloads.SyncPayload.class).isEmpty(),"explicit broadcast");});
      test("sync","disconnect event releases all subscriptions and locks",()->{var m=stored();editLock(editor,m,true);action(editor,action("fakeStatesRequest",null));action(editor,action("requestSync",null));ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(editor.connection,server);check(!((Set<?>)field(MachineMod.class,"fakeStateSubscribers")).contains(editor.getUUID()),"fake subscription cleared");check(!((Set<?>)field(MachineMod.class,"machineStateSubscribers")).contains(editor.getUUID()),"machine subscription cleared");check(MachineManager.editingLockedByOtherMachineId(m.id,"QAB_Admin")==null,"disconnect releases locks");});
   }
   private void persistenceTests(){
      test("persistence","save reload unicode revision and whitelist",()->{var m=stored();m.name="持久化测试 中文✓";MachineConfig.save();MachineConfig.load();eq(MachineConfig.getMachine(m.id).name,m.name,"UTF-8 persistence");eq(MachineConfig.getMachine(m.id).revision,m.revision,"revision persists");check(MachineConfig.getEditorWhitelist().contains("QAB_Editor"),"whitelist persists");check(!Files.exists(FabricLoader.getInstance().getConfigDir().resolve("command-gui-server/machines.json.tmp")),"atomic save leaves no temp");});
      test("persistence","legacy steps delay normalization detection migration",()->{var m=stored();var old=new Step();old.kind="legacy";old.delay=0;old.commandDelay=90000;old.description=null;old.commands=List.of("player {bot} spawn");m.onTimeline.steps=new ArrayList<>(List.of(old));m.detection.onValues=new ArrayList<>(List.of("true"));m.detection.offValues=new ArrayList<>(List.of("false"));MachineConfig.save();MachineConfig.load();var migrated=MachineConfig.getMachine(m.id);eq(migrated.onTimeline.steps.size(),2,"legacy becomes delay plus command");eq(migrated.onTimeline.steps.getFirst().delay,1,"delay minimum");eq(migrated.onTimeline.steps.get(1).commandDelay,72000,"command delay maximum");eq(migrated.detection.onValues,List.of("powered=true"),"legacy property prefix");});
      test("persistence","malformed config preserves loaded data",()->{Path file=FabricLoader.getInstance().getConfigDir().resolve("command-gui-server/machines.json");String backup=Files.readString(file);int count=MachineConfig.getMachines().size();try{Files.writeString(file,"not json");MachineConfig.load();eq(MachineConfig.getMachines().size(),count,"bad JSON does not clear data");}finally{Files.writeString(file,backup);MachineConfig.load();}});
   }
   private RuleData rule(String name)throws Exception{return CarpetRuleCatalog.read().stream().filter(r->r.manager().equals("carpet")&&r.name().equals(name)).findFirst().orElseThrow();}
   private String apply(ServerPlayer player,RuleData.Change...changes){receive(player,new RulePayloads.Apply(++request,GSON.toJson(changes)));return json(Hooks.packets(player,RulePayloads.Snapshot.class).getLast().json()).get("error").getAsString();}
   private void rejects(Runnable operation){boolean rejected=false;try{operation.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid rule command rejected");}
   private void interruptionTests(){
      async("scheduler","late first join and readiness gate retain pending commands",()->{current=stored();current.bots.set(0,"QAB_Late");current.detection.enabled=false;Hooks.blockReadiness=true;MachineScheduler.start(current,timeline("player {bot} spawn","player {bot} attack continuous","qabackend late-done"),"QAB_Admin",false);},()->{
         if(server.getPlayerList().getPlayerByName("QAB_Late")==null){check(marks.isEmpty(),"queue held before entity joins");return false;}
         if(lateJoinedTick<0)lateJoinedTick=clock;
         if(clock-lateJoinedTick<20){check(marks.isEmpty(),"queue held after join while action readiness is blocked");return false;}
         Hooks.blockReadiness=false;return !MachineScheduler.isRunning(current.id);
      },()->{eq(marks.stream().map(Mark::text).toList(),List.of("late-done"),"queue resumed exactly once");check(json(FakePlayerStateTracker.buildJson(server)).getAsJsonObject("players").getAsJsonObject("QAB_Late").get("attack").getAsBoolean(),"real Carpet action responded");},1000);
      for(boolean off:List.of(false,true)){
         String victim=off?"QAB_InterOff":"QAB_InterOn";
         async("scheduler","external removal aborts "+(off?"shutdown":"boot"),()->{check(FakePlayerStateTracker.isReady(server,server.getPlayerList().getPlayerByName(victim)),"victim ready before test");current=stored();current.detection.enabled=false;var t=timeline("player "+victim+" attack continuous","qabackend MUST_NOT_RUN");t.steps.getFirst().commandDelay=30;MachineScheduler.start(current,t,"QAB_Admin",off);},()->{if(clock-active.startTick==10)command("player "+victim+" kill");return !MachineScheduler.isRunning(current.id);},()->{check(server.getPlayerList().getPlayerByName(victim)==null,"victim actually removed");check(MachineScheduler.dumpExecTrace().stream().anyMatch(x->x.contains("OK   player "+victim)),"first command executed before interference");check(marks.isEmpty(),"no false completion effect");check(Hooks.messages(admin).contains("中止"),"failure feedback");check(!Hooks.messages(admin).contains("已开启")&&!Hooks.messages(admin).contains("已关闭"),"no success feedback");},200);
      }
      async("modes","failed mode cancels batch and does not report success",()->{current=stored();var a=mode(current,"a",false);a.onTimeline=timeline("player QAB_Bot spawn","qanoop");var b=mode(current,"b",false);current.modes.addAll(List.of(a,b));MachineModeChain.buildChains(current,List.of("a","b"),List.of(),"QAB_Admin");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->{check(marks.isEmpty(),"later mode not launched");check(!Hooks.messages(admin).contains("模式已切换"),"no batch success");},200);
      async("scheduler","intentional kill in shutdown is permitted",()->{current=stored();current.detection.enabled=false;MachineScheduler.start(current,timeline("player QAB_Low kill","qabackend deliberate-kill"),"QAB_Admin",true);},()->!MachineScheduler.isRunning(current.id),()->eq(marks.stream().map(Mark::text).toList(),List.of("deliberate-kill"),"script kill is expected"),150);
      async("scheduler","offline trigger preserves position and permissions",()->{var p=server.getPlayerList().getPlayerByName("QAB_Left");permission(p,4);command("tp QAB_Left 47 80 0");current=stored();current.detection.enabled=false;var t=timeline("qarestricted","qacontext","qabackend offline:{player}");Step d=new Step();d.kind="delay";d.delay=20;t.steps.addFirst(d);MachineScheduler.start(current,t,"QAB_Left",false);command("player QAB_Left kill");},()->!MachineScheduler.isRunning(current.id),()->{check(server.getPlayerList().getPlayerByName("QAB_Left")==null,"trigger really disconnected");eq(marks.stream().map(Mark::text).toList(),List.of("restricted","context:47","offline:QAB_Left"),"offline execution keeps original context");},150);
      async("scheduler","offline guest cannot acquire console permission",()->{var p=server.getPlayerList().getPlayerByName("QAB_Denied");permission(p,0);current=stored();current.detection.enabled=false;var t=timeline("qarestricted","qabackend MUST_NOT_RUN");Step delay=new Step();delay.kind="delay";delay.delay=20;t.steps.addFirst(delay);MachineScheduler.start(current,t,"QAB_Denied",false);command("player QAB_Denied kill");},()->!MachineScheduler.isRunning(current.id),()->{check(server.getPlayerList().getPlayerByName("QAB_Denied")==null,"guest disconnected");check(marks.isEmpty(),"no console escalation or later commands");},100);
      async("scheduler","literal spawn waits for command target instead of selected bot",()->{current=stored();current.detection.enabled=false;current.bots.set(0,"QAB_NotTarget");MachineScheduler.start(current,timeline("player QAB_Bot spawn","qabackend real-target"),"QAB_Admin",false);},()->!MachineScheduler.isRunning(current.id),()->eq(marks.stream().map(Mark::text).toList(),List.of("real-target"),"resolved command target awaited"),100);
      async("modes","queued modes continue after trigger disconnect",()->{var p=server.getPlayerList().getPlayerByName("QAB_Chain");permission(p,4);current=stored();current.modes.addAll(List.of(mode(current,"a",false),mode(current,"b",false)));current.modeInterval=30;MachineModeChain.buildChains(current,List.of("a","b"),List.of(),"QAB_Chain");command("player QAB_Chain kill");},()->!MachineModeChain.isActive(current.id)&&!MachineScheduler.hasRunning(),()->{check(server.getPlayerList().getPlayerByName("QAB_Chain")==null,"chain trigger disconnected");eq(marks.stream().map(Mark::text).toList(),List.of("start:a","start:b"),"all queued modes execute");},300);
      async("scheduler","detection mismatch cannot report successful boot",()->{current=stored();MachineScheduler.start(current,timeline("qabackend no-state-change"),"QAB_Admin",false);},()->!MachineScheduler.isRunning(current.id),()->{message(admin,"检测状态未达到");check(!Hooks.messages(admin).contains("已开启"),"state mismatch not success");},80);
   }
   private void ruleTests(){
      test("rules","dedicated and integrated permission matrix",()->{for(int level=0;level<=4;level++){var p=LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level));eq(RuleAccess.allowed(p,false,false),level>=1,"dedicated OP "+level);eq(RuleAccess.allowed(p,true,true),level>=1,"integrated cheats "+level);check(!RuleAccess.allowed(p,true,false),"integrated cheats required");}receive(guest,new RulePayloads.Query(++request));check(json(Hooks.packets(guest,RulePayloads.Snapshot.class).getLast().json()).getAsJsonArray("rules").isEmpty(),"guest sees no catalogue");check(apply(guest,new RuleData.Change("carpet:commandPlayer","true","set","false")).contains("权限不足"),"guest batch denied");});
      test("rules","real Carpet and Org catalogue categories values pagination throttle",()->{var catalogue=CarpetRuleCatalog.read();check(catalogue.size()>50,"real catalogue");check(catalogue.stream().anyMatch(r->r.name().equals("bindingCurseInvalidation")),"Org rule discovered (shares carpet manager)");check(catalogue.stream().anyMatch(r->r.options().size()>2),"nonboolean rules");check(catalogue.stream().allMatch(r->!r.categories().isEmpty()),"native categories");((Map<?,?>)field(CarpetRuleService.class,"lastQuery")).clear();receive(admin,new RulePayloads.Query(++request));var pages=Hooks.packets(admin,RulePayloads.Snapshot.class);int count=0;for(int i=0;i<pages.size();i++){var p=json(pages.get(i).json());eq(p.get("index").getAsInt(),i,"page index");eq(pages.get(i).request(),request,"correlation");count+=p.getAsJsonArray("rules").size();check(p.getAsJsonArray("rules").size()<=16,"bounded page");eq(p.get("last").getAsBoolean(),i==pages.size()-1,"last page marker");}eq(count,catalogue.size(),"complete catalogue");receive(admin,new RulePayloads.Query(++request));check(json(Hooks.packets(admin,RulePayloads.Snapshot.class).getLast().json()).get("error").getAsString().contains("频繁"),"rate limit");});
      test("rules","command validation and nonboolean values",()->{var r=new RuleData("carpet","qa","","",List.of("test"),List.of("test"),List.of("0","1","2"),"0","0","int",true,false);eq(r.command("set","2"),"carpet qa 2","numeric command");eq(r.command("setDefault","1"),"carpet setDefault qa 1","default command");eq(r.command("removeDefault",null),"carpet removeDefault qa","remove default");for(String v:Arrays.asList(null,"","3","true","1\nstop"," "))rejects(()->r.command("set",v));rejects(()->r.command("invalid","1"));var free=new RuleData("carpet","qa","","",List.of(),List.of(),List.of(),"","","String",false,false);rejects(()->free.command("set","x".repeat(257)));check(!RuleData.token("carpet\nstop"),"identifier control characters");});
      test("rules","locked manager rejects writes",()->{Object manager=Class.forName("carpet.CarpetServer").getField("settingsManager").get(null);var locked=Class.forName("carpet.api.settings.SettingsManager").getDeclaredField("locked");locked.setAccessible(true);boolean prior=locked.getBoolean(manager);var r=rule("flippinCactus");try{locked.setBoolean(manager,true);check(rule(r.name()).locked(),"lock advertised");check(apply(admin,new RuleData.Change(r.key(),r.value(),"set","true")).contains("未执行"),"locked batch rejected");eq(rule(r.name()).value(),r.value(),"locked value unchanged");}finally{locked.setBoolean(manager,prior);}});
      test("rules","OP level one query and nonboolean native mutation",()->{permission(guest,1);try{((Map<?,?>)field(CarpetRuleService.class,"lastQuery")).remove(guest.getUUID());receive(guest,new RulePayloads.Query(++request));check(!json(Hooks.packets(guest,RulePayloads.Snapshot.class).getFirst().json()).getAsJsonArray("rules").isEmpty(),"OP one may query");}finally{permission(guest,0);}var r=rule("pushLimit");try{apply(admin,new RuleData.Change(r.key(),r.value(),"set","14"));eq(rule(r.name()).value(),"14","integer rule applied");}finally{apply(admin,new RuleData.Change(r.key(),rule(r.name()).value(),"set",r.value()));}});
      test("rules","batch validation is atomic before execution and deduplicated",()->{var r=rule("flippinCactus");String next=r.value().equals("true")?"false":"true";check(apply(admin,new RuleData.Change(r.key(),r.value(),"set",next),new RuleData.Change("missing:rule","false","set","true")).contains("未执行"),"missing rule rejects entire batch");eq(rule(r.name()).value(),r.value(),"no partial change");check(apply(admin,new RuleData.Change(r.key(),"stale","set",next)).contains("未执行"),"stale snapshot rejected");var change=new RuleData.Change(r.key(),r.value(),"set",next);check(apply(admin,change,change).contains("未执行"),"duplicate rule rejected");check(apply(admin).contains("1–64"),"empty rejected");var many=new RuleData.Change[65];Arrays.fill(many,change);check(apply(admin,many).contains("1–64"),"batch bound");receive(admin,new RulePayloads.Apply(++request,"bad json"));check(!json(Hooks.packets(admin,RulePayloads.Snapshot.class).getLast().json()).get("error").getAsString().contains("已处理"),"malformed rejected");receive(admin,new RulePayloads.Apply(request,"[]"));check(json(Hooks.packets(admin,RulePayloads.Snapshot.class).getLast().json()).get("error").getAsString().contains("重复"),"batch replay rejected");});
      test("rules","real batch set and setDefault removeDefault",()->{var a=rule("flippinCactus");var b=rule("rotatorBlock");String av=a.value().equals("true")?"false":"true",bv=b.value().equals("true")?"false":"true";check(apply(admin,new RuleData.Change(a.key(),a.value(),"set",av),new RuleData.Change(b.key(),b.value(),"set",bv)).contains("已处理"),"batch accepted");eq(rule(a.name()).value(),av,"first applied");eq(rule(b.name()).value(),bv,"second applied");apply(admin,new RuleData.Change(a.key(),av,"setDefault",av));Path conf=server.getWorldPath(LevelResource.ROOT).resolve("carpet.conf");check(Files.readString(conf).contains("flippinCactus "+av),"default saved on disk");apply(admin,new RuleData.Change(a.key(),av,"removeDefault",null));check(!Files.readString(conf).contains("flippinCactus "),"default removed");apply(admin,new RuleData.Change(a.key(),rule(a.name()).value(),"set",a.value()),new RuleData.Change(b.key(),bv,"set",b.value()));});
      test("persistence","prepare cross-process sentinel",()->{var m=stored();m.id="qa_restart_sentinel";m.name="重启后端验证";MachineConfig.save();var r=rule("flippinCactus");apply(admin,new RuleData.Change(r.key(),r.value(),"setDefault","true"));check(Files.readString(server.getWorldPath(LevelResource.ROOT).resolve("carpet.conf")).contains("flippinCactus true"),"restart default written");});
   }
   private void registerRestart(){
      test("persistence","fresh server reloads machine whitelist and Carpet default",()->{var m=MachineConfig.getMachine("qa_restart_sentinel");check(m!=null,"machine survives process restart");eq(m.name,"重启后端验证","unicode survives restart");check(MachineConfig.getEditorWhitelist().contains("QAB_Editor"),"whitelist survives restart");eq(rule("flippinCactus").value(),"true","setDefault applies after restart");check(!MachineScheduler.hasRunning(),"no stale scheduler");check(!MachineMod.hasMachineStateSubscribers(),"no stale subscribers");});
   }
   private void finish(){if(finished)return;finished=true;if(phase.equals("suite"))for(String group:GROUPS)if(results.stream().noneMatch(r->r.group.equals(group)))results.add(new Result(group,"coverage guard","FAIL",0,"No cases executed for required group"));write(true);server.halt(false);}
   private void write(boolean complete){try{var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("complete",complete);report.put("passed",results.stream().filter(r->r.status.equals("PASS")).count());report.put("failed",results.stream().filter(r->r.status.equals("FAIL")).count());report.put("assertions",assertions);report.put("cases",results);Files.writeString(run.resolve("backend-"+phase+".json"),GSON.toJson(report));}catch(Exception e){throw new RuntimeException("Cannot write test report",e);}}
}


