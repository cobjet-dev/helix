package com.linkedin.helix.webapp;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.I0Itec.zkclient.IDefaultNameSpace;
import org.I0Itec.zkclient.ZkServer;
import org.apache.commons.io.FileUtils;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.restlet.Client;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.resource.Representation;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.model.InstanceConfig.InstanceConfigProperty;
import com.linkedin.helix.webapp.resources.ClusterRepresentationUtil;
import com.linkedin.helix.webapp.resources.ClustersResource;
import com.linkedin.helix.webapp.resources.HostedResourceGroupsResource;
import com.linkedin.helix.webapp.resources.IdealStateResource;
import com.linkedin.helix.webapp.resources.InstancesResource;

public class TestClusterManagementWebapp
{
  private String _zkServerAddress;
  private List<ZkServer> _localZkServers;

  RestAdminApplication _adminApp;
  Component _component;

  int _port = 2200;

  public static List<ZkServer> startLocalZookeeper(List<Integer> localPortsList,
      String zkTestDataRootDir, int tickTime) throws IOException
  {
    List<ZkServer> localZkServers = new ArrayList<ZkServer>();

    int count = 0;
    for (int port : localPortsList)
    {
      ZkServer zkServer = startZkServer(zkTestDataRootDir, count++, port, tickTime);
      localZkServers.add(zkServer);
    }
    return localZkServers;
  }

  public static ZkServer startZkServer(String zkTestDataRootDir, int machineId, int port,
      int tickTime) throws IOException
  {
    File zkTestDataRootDirFile = new File(zkTestDataRootDir);
    zkTestDataRootDirFile.mkdirs();

    String dataPath = zkTestDataRootDir + "/" + machineId + "/" + port + "/data";
    String logPath = zkTestDataRootDir + "/" + machineId + "/" + port + "/log";

    FileUtils.deleteDirectory(new File(dataPath));
    FileUtils.deleteDirectory(new File(logPath));

    IDefaultNameSpace mockDefaultNameSpace = new IDefaultNameSpace() {
      @Override
      public void createDefaultNameSpace(org.I0Itec.zkclient.ZkClient zkClient)
      {
      }
    };

    ZkServer zkServer = new ZkServer(dataPath, logPath, mockDefaultNameSpace, port, tickTime);
    zkServer.start();
    return zkServer;
  }

  public static void stopLocalZookeeper(List<ZkServer> localZkServers)
  {
    for (ZkServer zkServer : localZkServers)
    {
      zkServer.shutdown();
    }
  }

  void startAdminWebAppThread() throws Exception
  {
    Thread t = new Thread(new Runnable() {
      @Override
      public void run()
      {
        try
        {
          _component = new Component();
          _component.getServers().add(Protocol.HTTP, _port);
          Context applicationContext = _component.getContext().createChildContext();
          applicationContext.getAttributes().put(RestAdminApplication.ZKSERVERADDRESS,
              _zkServerAddress);

          _adminApp = new RestAdminApplication(applicationContext);
          // Attach the application to the component and start it
          _component.getDefaultHost().attach(_adminApp);
          _component.start();

        } catch (Exception e)
        {
          e.printStackTrace();
        }
      }
    });
    t.start();
  }

  @BeforeTest
  public void setup() throws IOException, Exception
  {
    List<Integer> localPorts = new ArrayList<Integer>();
    localPorts.add(2199);

    _localZkServers = startLocalZookeeper(localPorts, System.getProperty("user.dir") + "/"
        + "zkdata", 2000);
    _zkServerAddress = "localhost:" + 2199;

    System.out.println("zk started!!");
    startAdminWebAppThread();
    System.out.println("WebApp started!!");
  }

  // @AfterMethod
  @AfterTest
  public void tearDown() throws Exception
  {
    stopLocalZookeeper(_localZkServers);
    System.out.println("zk stopped!!");
  }

  @Test
  public void testInvocation() throws Exception
  {
    verifyAddCluster();
    verifyAddStateModel();
    verifyAddHostedEntity();
    verifyAddInstance();
    verifyRebalance();
    verifyEnableInstance();
    verifyAlterIdealState();
    verifyConfigAccessor();

    System.out.println("Test passed!!");
  }

  /*
   * Test case as steps
   */
  String clusterName = "cluster-12345";
  String resourceGroupName = "new-entity-12345";
  String instance1 = "test-1";
  String statemodel = "state_model";
  int instancePort = 9999;
  int partitions = 10;
  int replicas = 3;

  void verifyAddStateModel() throws JsonGenerationException, JsonMappingException, IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/StateModelDefs";
    Map<String, String> paraMap = new HashMap<String, String>();

    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._addStateModelCommand);

    ZNRecord r = new ZNRecord(statemodel);

    Reference resourceRef = new Reference(httpUrlBase);
    Request request = new Request(Method.POST, resourceRef);
    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap) + "&"
            + ClusterRepresentationUtil._newModelDef + "="
            + ClusterRepresentationUtil.ZNRecordToJson(r), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord zn = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(zn.getListFields().get("models").contains(statemodel));
  }

  void verifyAddCluster() throws IOException, InterruptedException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/";
    Map<String, String> paraMap = new HashMap<String, String>();

    paraMap.put(ClustersResource._clusterName, clusterName);
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._addClusterCommand);

    Reference resourceRef = new Reference(httpUrlBase);

    // resourceRef.addQueryParameter(ClusterRepresentationUtil._jsonParameters,
    // ClusterRepresentationUtil.ObjectToJson(paraMap));
    Request request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord zn = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(zn.getListField("clusters").contains(clusterName));
    // Thread.currentThread().join();

  }

  void verifyAddHostedEntity() throws JsonGenerationException, JsonMappingException, IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/resourceGroups";
    Map<String, String> paraMap = new HashMap<String, String>();

    paraMap.put(HostedResourceGroupsResource._resourceGroupName, resourceGroupName);
    paraMap.put(HostedResourceGroupsResource._partitions, "" + partitions);
    paraMap.put(HostedResourceGroupsResource._stateModelDefRef, "MasterSlave");
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._addResourceGroupCommand);

    Reference resourceRef = new Reference(httpUrlBase);

    Request request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord zn = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(zn.getListField("ResourceGroups").contains(resourceGroupName));

    httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName + "/resourceGroups/"
        + resourceGroupName;
    resourceRef = new Reference(httpUrlBase);

    request = new Request(Method.GET, resourceRef);

    client = new Client(Protocol.HTTP);
    response = client.handle(request);

    result = response.getEntity();
    sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());
  }

  void verifyAddInstance() throws JsonGenerationException, JsonMappingException, IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName + "/instances";
    Map<String, String> paraMap = new HashMap<String, String>();
    // Add 1 instance
    paraMap.put(InstancesResource._instanceName, instance1 + ":" + instancePort);
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._addInstanceCommand);

    Reference resourceRef = new Reference(httpUrlBase);

    Request request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();

    TypeReference<ArrayList<ZNRecord>> typeRef = new TypeReference<ArrayList<ZNRecord>>() {
    };
    List<ZNRecord> znList = mapper.readValue(new StringReader(sw.toString()), typeRef);
    AssertJUnit.assertTrue(znList.get(0).getId().equals(instance1 + "_" + instancePort));

    // the case to add more than 1 instances
    paraMap.clear();
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._addInstanceCommand);

    String[] instances = { "test2", "test3", "test4", "test5" };

    String instanceNames = "";
    boolean first = true;
    for (String instance : instances)
    {
      if (first == true)
      {
        first = false;
      } else
      {
        instanceNames += ";";
      }
      instanceNames += (instance + ":" + instancePort);
    }
    paraMap.put(InstancesResource._instanceNames, instanceNames);

    request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    client = new Client(Protocol.HTTP);
    response = client.handle(request);

    result = response.getEntity();
    sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    mapper = new ObjectMapper();

    znList = mapper.readValue(new StringReader(sw.toString()), typeRef);

    for (String instance : instances)
    {
      boolean found = false;
      for (ZNRecord r : znList)
      {
        String instanceId = instance + "_" + instancePort;
        if (r.getId().equals(instanceId))
        {
          found = true;
          break;
        }
      }
      AssertJUnit.assertTrue(found);
    }
  }

  void verifyRebalance() throws JsonGenerationException, JsonMappingException, IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/resourceGroups/" + resourceGroupName + "/idealState";
    Map<String, String> paraMap = new HashMap<String, String>();
    // Add 1 instance
    paraMap.put(IdealStateResource._replicas, "" + replicas);
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._rebalanceCommand);

    Reference resourceRef = new Reference(httpUrlBase);

    Request request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord r = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);

    for (int i = 0; i < partitions; i++)
    {
      String partitionName = resourceGroupName + "_" + i;
      assert (r.getMapField(partitionName).size() == replicas);
    }

    httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName;
    resourceRef = new Reference(httpUrlBase);
    request = new Request(Method.GET, resourceRef);

    client = new Client(Protocol.HTTP);
    response = client.handle(request);

    result = response.getEntity();
    sw = new StringWriter();
    result.write(sw);

  }

  void verifyEnableInstance() throws JsonGenerationException, JsonMappingException, IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName + "/instances/"
        + instance1 + "_" + instancePort;
    Map<String, String> paraMap = new HashMap<String, String>();
    // Add 1 instance
    paraMap.put(ClusterRepresentationUtil._enabled, "" + false);
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._enableInstanceCommand);

    Reference resourceRef = new Reference(httpUrlBase);

    Request request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord r = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(r.getSimpleField(InstanceConfigProperty.HELIX_ENABLED.toString())
        .equals("" + false));

    // Then enable it
    paraMap.put(ClusterRepresentationUtil._enabled, "" + true);
    request = new Request(Method.POST, resourceRef);

    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap), MediaType.APPLICATION_ALL);
    client = new Client(Protocol.HTTP);
    response = client.handle(request);

    result = response.getEntity();
    sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    mapper = new ObjectMapper();
    r = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(r.getSimpleField(InstanceConfigProperty.HELIX_ENABLED.toString())
        .equals("" + true));
  }

  void verifyAlterIdealState() throws IOException
  {
    String httpUrlBase = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/resourceGroups/" + resourceGroupName + "/idealState";

    Reference resourceRef = new Reference(httpUrlBase);
    Request request = new Request(Method.GET, resourceRef);

    Client client = new Client(Protocol.HTTP);
    Response response = client.handle(request);

    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    ObjectMapper mapper = new ObjectMapper();
    ZNRecord r = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    String partitionName = "new-entity-12345_3";
    r.getMapFields().remove(partitionName);

    Map<String, String> paraMap = new HashMap<String, String>();
    // Add 1 instance
    paraMap.put(ClusterRepresentationUtil._managementCommand,
        ClusterRepresentationUtil._alterIdealStateCommand);

    resourceRef = new Reference(httpUrlBase);

    request = new Request(Method.POST, resourceRef);
    request.setEntity(
        ClusterRepresentationUtil._jsonParameters + "="
            + ClusterRepresentationUtil.ObjectToJson(paraMap) + "&"
            + ClusterRepresentationUtil._newIdealState + "="
            + ClusterRepresentationUtil.ZNRecordToJson(r), MediaType.APPLICATION_ALL);
    client = new Client(Protocol.HTTP);
    response = client.handle(request);

    result = response.getEntity();
    sw = new StringWriter();
    result.write(sw);

    System.out.println(sw.toString());

    mapper = new ObjectMapper();
    ZNRecord r2 = mapper.readValue(new StringReader(sw.toString()), ZNRecord.class);
    AssertJUnit.assertTrue(!r2.getMapFields().containsKey(partitionName));

    for (String key : r2.getMapFields().keySet())
    {
      AssertJUnit.assertTrue(r.getMapFields().containsKey(key));
    }
  }

  // verify get/post configs in different scopes
  void verifyConfigAccessor() throws Exception
  {
    ObjectMapper mapper = new ObjectMapper();
    Client client = new Client(Protocol.HTTP);

    // set/get cluster scope configs
    String url = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/configs/cluster/" + clusterName;

    postConfig(client, url, mapper, ClusterRepresentationUtil._setConfig, "key1=value1,key2=value2");

    ZNRecord record = get(client, url, mapper);
    Assert.assertEquals(record.getSimpleFields().size(), 2);
    Assert.assertEquals(record.getSimpleField("key1"), "value1");
    Assert.assertEquals(record.getSimpleField("key2"), "value2");

    // set/get participant scope configs
    url = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/configs/participant/localhost_12918";

    postConfig(client, url, mapper, ClusterRepresentationUtil._setConfig, "key3=value3,key4=value4");

    record = get(client, url, mapper);
    Assert.assertEquals(record.getSimpleFields().size(), 2);
    Assert.assertEquals(record.getSimpleField("key3"), "value3");
    Assert.assertEquals(record.getSimpleField("key4"), "value4");

    // set/get resource scope configs
    url = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/configs/resource/testResource";

    postConfig(client, url, mapper, ClusterRepresentationUtil._setConfig, "key5=value5,key6=value6");

    record = get(client, url, mapper);
    Assert.assertEquals(record.getSimpleFields().size(), 2);
    Assert.assertEquals(record.getSimpleField("key5"), "value5");
    Assert.assertEquals(record.getSimpleField("key6"), "value6");

    // set/get partition scope configs
    url = "http://localhost:" + _port + "/clusters/" + clusterName
        + "/configs/partition/testResource/testPartition";

    postConfig(client, url, mapper, ClusterRepresentationUtil._setConfig, "key7=value7,key8=value8");

    record = get(client, url, mapper);
    Assert.assertEquals(record.getSimpleFields().size(), 2);
    Assert.assertEquals(record.getSimpleField("key7"), "value7");
    Assert.assertEquals(record.getSimpleField("key8"), "value8");

    // list keys
    url = "http://localhost:" + _port + "/clusters/" + clusterName + "/configs";
    record = get(client, url, mapper);
    Assert.assertEquals(record.getListFields().size(), 1);
    Assert.assertTrue(record.getListFields().containsKey("scopes"));
    Assert.assertTrue(contains(record.getListField("scopes"), "CLUSTER", "PARTICIPANT", "RESOURCE", "PARTITION"));

    url = "http://localhost:" + _port + "/clusters/" + clusterName + "/configs/cluster";
    record = get(client, url, mapper);
    Assert.assertEquals(record.getListFields().size(), 1);
    Assert.assertTrue(record.getListFields().containsKey("CLUSTER"));
    Assert.assertTrue(contains(record.getListField("CLUSTER"), clusterName));

    url = "http://localhost:" + _port + "/clusters/" + clusterName + "/configs/participant";
    record = get(client, url, mapper);
    Assert.assertTrue(record.getListFields().containsKey("PARTICIPANT"));
    Assert.assertTrue(contains(record.getListField("PARTICIPANT"), "localhost_12918"));

    url = "http://localhost:" + _port + "/clusters/" + clusterName + "/configs/resource";
    record = get(client, url, mapper);
    Assert.assertEquals(record.getListFields().size(), 1);
    Assert.assertTrue(record.getListFields().containsKey("RESOURCE"));
    Assert.assertTrue(contains(record.getListField("RESOURCE"), "testResource"));

    url = "http://localhost:" + _port + "/clusters/" + clusterName + "/configs/partition/testResource";
    record = get(client, url, mapper);
    Assert.assertEquals(record.getListFields().size(), 1);
    Assert.assertTrue(record.getListFields().containsKey("PARTITION"));
    Assert.assertTrue(contains(record.getListField("PARTITION"), "testPartition"));

  }

  private ZNRecord get(Client client, String url, ObjectMapper mapper) throws Exception
  {
    Request request = new Request(Method.GET, new Reference(url));
    Response response = client.handle(request);
    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);
    String responseStr = sw.toString();
    Assert.assertTrue(responseStr.toLowerCase().indexOf("error") == -1);
    Assert.assertTrue(responseStr.toLowerCase().indexOf("exception") == -1);

    ZNRecord record = mapper.readValue(new StringReader(responseStr), ZNRecord.class);
    return record;
  }

  private void postConfig(Client client, String url, ObjectMapper mapper, String command, String configs) throws Exception
  {
    Map<String, String> params = new HashMap<String, String>();

    params.put(ClusterRepresentationUtil._managementCommand, command);
    params.put("configs", configs);

    Request request = new Request(Method.POST, new Reference(url));
    request.setEntity(ClusterRepresentationUtil._jsonParameters + "="
        + ClusterRepresentationUtil.ObjectToJson(params), MediaType.APPLICATION_ALL);

    Response response = client.handle(request);
    Representation result = response.getEntity();
    StringWriter sw = new StringWriter();
    result.write(sw);
    String responseStr = sw.toString();
    Assert.assertTrue(responseStr.toLowerCase().indexOf("error") == -1);
    Assert.assertTrue(responseStr.toLowerCase().indexOf("exception") == -1);
  }

  private boolean contains(List<String> list, String... items)
  {
    for (String item : items)
    {
      if (!list.contains(item))
      {
        return false;
      }
    }
    return true;
  }
}