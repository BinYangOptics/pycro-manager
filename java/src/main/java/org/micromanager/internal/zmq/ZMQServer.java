package org.micromanager.internal.zmq;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import static org.micromanager.internal.zmq.ZMQUtil.EXTERNAL_OBJECTS;

import org.zeromq.SocketType;

/**
 * implements request reply server (ie the reply part)
 *
 * ecompasses both the master server and the
 */
public class ZMQServer extends ZMQSocketWrapper {

   private ExecutorService executor_;
//   protected static Set<Class> apiClasses_;
   private static Set<String> packages_;
   private static ZMQUtil util_;

   public static final String VERSION = "2.7.0";

   private static Function<Class, Object> classMapper_;
   private static ZMQServer masterServer_;
   static boolean debug_ = false;

   //for testing
//   public static void main(String[] args) {
//      ZMQServer server = new ZMQServer(DEFAULT_MASTER_PORT_NUMBER, "master", new Function<Class, Object>() {
//         @Override
//         public Object apply(Class t) {
//            return null;
//         }
//      });
//      while (true) {
//         if (portSocketMap_.containsKey(DEFAULT_MASTER_PORT_NUMBER + 1)) {
//            ZMQPullSocket socket = (ZMQPullSocket) portSocketMap_.get(DEFAULT_MASTER_PORT_NUMBER + 1);
//            Object n = socket.next();
//            System.out.println();
//         }
//      }
//   }

   /**
    * This constructor used if making a new server on a different port and all the classloader info already parsed
    */
   public ZMQServer()  {
      super(SocketType.REP);
   }

   public ZMQServer(Collection<ClassLoader> cls, Function<Class, Object> classMapper,
                    String[] excludePaths) throws URISyntaxException, UnsupportedEncodingException {
      super(SocketType.REP);
      classMapper_ = classMapper;
      util_ = new ZMQUtil(cls, excludePaths);

      //get packages for current classloader (redundant?)
      packages_ = ZMQUtil.getPackages();
      for (ClassLoader cl : cls) {
         packages_.addAll(ZMQUtil.getPackagesFromJars((URLClassLoader) cl));
      }
   }

   public static ZMQServer getMasterServer() {
      return masterServer_;
   }

   @Override
   public void initialize(int port) {
      // Can we be initialized multiple times?  If so, we should cleanup
      // the multiple instances of executors and sockets cleanly
      executor_ = Executors.newSingleThreadExecutor(
              (Runnable r) -> new Thread(r, "ZMQ Server "));
      executor_.submit(() -> {
         socket_ = context_.createSocket(type_);
         port_ = port;
         socket_.bind("tcp://127.0.0.1:" + port);

         //Master request-reply loop
         while (true) {
            String message = socket_.recvStr();
            if (debug_) {
               System.out.println("Recieved message: \t" + message);
            }
            byte[] reply = null;
            try {
               reply = parseAndExecuteCommand(message);
            } catch (Exception e) {
               try {
                  JSONObject json = new JSONObject();
                  json.put("type", "exception");

                  StringWriter sw = new StringWriter();
                  e.printStackTrace(new PrintWriter(sw));
                  String exceptionAsString = sw.toString();
                  json.put("value", exceptionAsString);

                  reply = json.toString().getBytes();
                  e.printStackTrace();

               } catch (JSONException ex) {
                  throw new RuntimeException(ex);
                  // This wont happen          
               }
            }
            if (debug_) {
               System.out.println("Sending message: \t" + new String(reply));
            }
            socket_.send(reply);
            if (debug_) {
               System.out.println("Message sent");
            }
         }
      });
   }

   public void close() {
      if (executor_ != null) {
         executor_.shutdownNow();
         socket_.close();
      }
   }

   protected byte[] getField(Object obj, JSONObject json) throws JSONException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
      String fieldName = json.getString("name");
      Object field = obj.getClass().getField(fieldName).get(obj);
      JSONObject serialized = new JSONObject();
      util_.serialize(field, serialized, port_);
      return serialized.toString().getBytes();
   }
   
   protected void setField(Object obj, JSONObject json) throws JSONException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
      String fieldName = json.getString("name");
      Object val = json.get("value");
      if (val instanceof JSONObject) {
         val = EXTERNAL_OBJECTS.get(((JSONObject) val).getString("hash-code"));
      }
      obj.getClass().getField(fieldName).set(obj, val);
   }

   private LinkedList<LinkedList<Class>> getParamCombos(JSONObject message, Object[] argVals) throws JSONException,
           UnsupportedEncodingException {

      Object[] argClasses = new Object[message.getJSONArray("arguments").length()];
      for (int i = 0; i < argVals.length; i++) {
//         Class c = message.getJSONArray("arguments").get(i).getClass();
         if (message.getJSONArray("arguments").get(i) instanceof JSONObject
                 && message.getJSONArray("arguments").getJSONObject(i).has("hash-code")) {
            //Passed in a javashadow object as an argument
            argVals[i] = EXTERNAL_OBJECTS.get(
                    message.getJSONArray("arguments").getJSONObject(i).get("hash-code"));
            //abstract to superclasses/interfaces in the API
            Set<String> potentialPackages = new TreeSet<String>();
            Class clazz = argVals[i].getClass();
            while (clazz.getSuperclass() != null) {
               for (Class c : clazz.getInterfaces()){
                  potentialPackages.add(c.getPackage().getName());
               }
               potentialPackages.add(clazz.getPackage().getName());
               clazz = clazz.getSuperclass();
            }
            //build up a list of valid packages
            Set<Class> apiClasses = new HashSet<Class>();
            for (String packageName : potentialPackages) {
               apiClasses.addAll(util_.getPackageClasses(packageName));
            }

            ParamSet<Class> potentialClasses = new ParamSet<Class>();
            for (Class apiClass : apiClasses) {
               if (apiClass.isAssignableFrom(argVals[i].getClass())) {
                  potentialClasses.add(apiClass);
               }
            }
            //add the class itself. This is needed for java internal classes
            potentialClasses.add(argVals[i].getClass());
            argClasses[i] = potentialClasses;
         } else if (ZMQUtil.PRIMITIVE_NAME_CLASS_MAP.containsKey(message.getJSONArray("argument-types").get(i))) {
            argClasses[i] = ZMQUtil.PRIMITIVE_NAME_CLASS_MAP.get(
                    message.getJSONArray("argument-types").get(i));         
            Object primitive = message.getJSONArray("arguments").get(i); //Double, Integer, Long, Boolean
            argVals[i] = ZMQUtil.convertToPrimitiveClass(primitive, (Class) argClasses[i]);            
         } else if (message.getJSONArray("argument-types").get(i).equals("java.lang.String")) {
            //Strings are a special case because they're like a primitive but not quite
            argClasses[i] = java.lang.String.class;
            if (message.getJSONArray("arguments").get(i) == JSONObject.NULL) {
               argVals[i] = null;
            } else {
               argVals[i] = message.getJSONArray("arguments").getString(i);
            }
         } else if (message.getJSONArray("argument-types").get(i).equals("java.lang.Object")) {
            argClasses[i] = java.lang.Object.class;
            argVals[i] = message.getJSONArray("arguments").get(i);
         }
      }

      //Generate every possible combination of parameters given multiple interfaces for classes
      //so that the correct method can be located
      LinkedList<LinkedList<Class>> paramCombos = new LinkedList<LinkedList<Class>>();
      for (Object argument : argClasses) {
         if (argument instanceof ParamSet) {
            if (paramCombos.isEmpty()) {
               //Add an entry for each possible type of the argument
               for (Class c : (ParamSet<Class>) argument) {
                  paramCombos.add(new LinkedList<Class>());
                  paramCombos.getLast().add(c);
               }
            } else {
               //multiply each existing combo by each possible value of the arg
               LinkedList<LinkedList<Class>> newComboList = new LinkedList<LinkedList<Class>>();
               for (Class c : (ParamSet<Class>) argument) {
                  for (LinkedList<Class> argList : paramCombos) {
                     LinkedList<Class> newArgList = new LinkedList<Class>(argList);
                     newArgList.add(c);
                     newComboList.add(newArgList);
                  }
               }
               paramCombos = newComboList;
            }
         } else {
            //only one type, simply add it to every combo
            if (paramCombos.isEmpty()) {
               //Add an entry for each possible type of the argument
               paramCombos.add(new LinkedList<Class>());
            }
            for (LinkedList<Class> argList : paramCombos) {
               argList.add((Class) argument);
            }
         }
      }
      return paramCombos;
   }

   private Object runConstructor(JSONObject message, Class baseClass) throws
           JSONException, InstantiationException, IllegalAccessException,
           IllegalArgumentException, InvocationTargetException, UnsupportedEncodingException {

      Object[] argVals = new Object[message.getJSONArray("arguments").length()];

      LinkedList<LinkedList<Class>> paramCombos = getParamCombos(message, argVals);

      Constructor mathcingConstructor = null;
      if (paramCombos.isEmpty()) { //Constructor with no argumetns
         try {
            mathcingConstructor = baseClass.getConstructor(new Class[]{});
         } catch (Exception ex) {
            throw new RuntimeException(ex);
         }
      } else { //Figure out which constructor matches given argumetns
         for (LinkedList<Class> argList : paramCombos) {
            Class[] classArray = argList.stream().toArray(Class[]::new);
            try {
               mathcingConstructor = baseClass.getConstructor(classArray);
               break;
            } catch (NoSuchMethodException e) {
               //ignore
            }
         }
      }
      if (mathcingConstructor == null) {
         throw new RuntimeException("No Matching method found with argumetn types");
      }

      return mathcingConstructor.newInstance(argVals);
   }

   private byte[] runMethod(Object obj, JSONObject message) throws NoSuchMethodException, IllegalAccessException,
           JSONException, UnsupportedEncodingException {
      String methodName = message.getString("name");
      Object[] argVals = new Object[message.getJSONArray("arguments").length()];
      LinkedList<LinkedList<Class>> paramCombos = getParamCombos(message, argVals);

      Method matchingMethod = null;
      if (paramCombos.isEmpty()) {
         //0 argument funtion
         matchingMethod = obj.getClass().getMethod(methodName);
      } else {
         for (LinkedList<Class> argList : paramCombos) {
            Class[] classArray = argList.stream().toArray(Class[]::new);
            try {
               matchingMethod = obj.getClass().getMethod(methodName, classArray);
               break;
            } catch (NoSuchMethodException e) {
               //ignore
            }
         }
      }
      if (matchingMethod == null) {
         throw new RuntimeException("No Matching method found with argumetn types");
      }

      Object result;
      try {
         matchingMethod.setAccessible(true); //this is needed to call public methods on private classes
         result = matchingMethod.invoke(obj, argVals);
      } catch (InvocationTargetException ex) {
         ex.printStackTrace();
         result = ex.getCause();
      }

      JSONObject serialized = new JSONObject();
      util_.serialize(result, serialized, port_);
      return serialized.toString().getBytes();
   }

   protected byte[] parseAndExecuteCommand(String message) throws Exception {
      JSONObject request = new JSONObject(message);
      JSONObject reply;
      switch (request.getString("command")) {
         case "connect": {//Connect to master server
            masterServer_ = this;
            debug_ = request.getBoolean("debug");
            //Called by master process
            reply = new JSONObject();
            reply.put("type", "none");
            reply.put("version", VERSION);
            return reply.toString().getBytes();
         }
         case "get-constructors": {
            String classpath = request.getString("classpath");
            reply = new JSONObject();
            reply.put("type", "none");
            reply.put("api", ZMQUtil.parseConstructors(classpath, classMapper_));
            return reply.toString().getBytes();
         }
         case "constructor": { //construct a new object (or grab an exisitng instance)
            Class baseClass = util_.loadClass(request.getString("classpath"));

            if (baseClass == null) {
               throw new RuntimeException("Couldnt find class with name" + request.getString("classpath"));
            }

            Object instance = classMapper_.apply(baseClass);
            //if this is not one of the classes that is supposed to grab an existing 
            //object, construct a new one
            if (instance == null) {
               instance = runConstructor(request, baseClass);
            }

            if (request.has("new-port") && request.getBoolean("new-port")) {
               //start the server for this class and store it
               new ZMQServer();
            }
            reply = new JSONObject();
            util_.serialize(instance, reply, port_);
            return reply.toString().getBytes();
         }
         case "run-method": {
            String hashCode = request.getString("hash-code");
            Object target = EXTERNAL_OBJECTS.get(hashCode);
            return runMethod(target, request);
         }
         case "get-field": {
            String hashCode = request.getString("hash-code");
            Object target = EXTERNAL_OBJECTS.get(hashCode);
            return getField(target, request);
         }
         case "set-field": {
            String hashCode = request.getString("hash-code");
            Object target = EXTERNAL_OBJECTS.get(hashCode);
            setField(target, request);
            reply = new JSONObject();
            reply.put("type", "none");
            return reply.toString().getBytes();
         }
         case "destructor": {
            String hashCode = request.getString("hash-code");
            //TODO this is defined in superclass, maybe it would be good to merge these?
//            System.out.println("remove object: " + hashCode);
            Object removed = EXTERNAL_OBJECTS.remove(hashCode);
            reply = new JSONObject();

            reply.put("type", "none");
            return reply.toString().getBytes();
         }
         default:
            break;
      }
      throw new RuntimeException("Unknown Command");
   }

}

class ParamSet<E> extends HashSet<E> {

}
