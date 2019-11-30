/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.bee.dbcp.pool.util;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedList;

import cn.bee.dbcp.pool.FastConnectionPool;
import cn.bee.dbcp.pool.ProxyConnectionBase;
import cn.bee.dbcp.pool.ProxyCsStatementBase;
import cn.bee.dbcp.pool.ProxyDatabaseMetaDataBase;
import cn.bee.dbcp.pool.ProxyPsStatementBase;
import cn.bee.dbcp.pool.ProxyResultSetBase;
import cn.bee.dbcp.pool.ProxyStatementBase;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.LoaderClassPath;
import javassist.Modifier;

/**
 * An independent execution toolkit class to generate JDBC proxy classes with javassist,
 * then write to class folder.
 * 
 * @author Chris.Liao
 * @version 1.0
 */
@SuppressWarnings("unchecked")
public final class ProxyClassUtil {
	
	/**
	 * default classes output folder in project
	 */
	private static String folder ="BeeCP/target/classes";
	
	/**
	 * @param args take the first argument as classes generated output folder,otherwise take default folder
	 * 
	 * @throws Exception throw exception in generating process
	 */
	public static void main(String[] args) throws Exception {
		if (args != null && args.length > 0)
			folder = args[0];
		
		writeProxyFile(folder);
	}

	/**
	 * write to disk folder
	 * @param folder classes generated will write to it
	 * @throws Exception if failed to write file to disk
	 */
	public static void writeProxyFile(String folder) throws Exception {
		ProxyClassUtil builder = new ProxyClassUtil();
		CtClass[] ctClasses = builder.createJdbcProxyClasses();
		for (int i = 0; i < ctClasses.length; i++) {
			ctClasses[i].writeFile(folder);
		}
	}

  /**
   * create all wrapper classes based on JDBC some interfaces
   *  @throws Exception if failed to generate class
   *  @return a class array generated by javassist
   *  
   * new Class:
   * cn.bee.dbcp.pool.ProxyConnection
   * cn.bee.dbcp.pool.ProxyStatement
   * cn.bee.dbcp.pool.ProxyPsStatement
   * cn.bee.dbcp.pool.ProxyCsStatement
   * cn.bee.dbcp.pool.ProxyResultSet 
   */
	public CtClass[] createJdbcProxyClasses() throws Exception {
		try{
			ClassPool classPool = ClassPool.getDefault();
			classPool.importPackage("java.sql");
			classPool.importPackage("cn.bee.dbcp.pool");
			classPool.appendClassPath(new LoaderClassPath(this.getClass().getClassLoader()));
			
			//............Connection Begin.........
			CtClass ctConIntf = classPool.get(Connection.class.getName());
			CtClass ctConSuperclass = classPool.get(ProxyConnectionBase.class.getName());
			String ctConIntfProxyClassName ="cn.bee.dbcp.pool.ProxyConnection";
			CtClass ctConIntfProxyImplClass = classPool.makeClass(ctConIntfProxyClassName,ctConSuperclass);
			ctConIntfProxyImplClass.setInterfaces(new CtClass[]{ctConIntf});
			ctConIntfProxyImplClass.setModifiers(Modifier.FINAL);
			ctConIntfProxyImplClass.setModifiers(Modifier.PUBLIC);
			
			CtClass[] conCreateParam = new CtClass[]{
					classPool.get("cn.bee.dbcp.pool.PooledConnection")};	
		
			CtConstructor subClassConstructor = new CtConstructor(conCreateParam,ctConIntfProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			StringBuilder body = new StringBuilder();
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctConIntfProxyImplClass.addConstructor(subClassConstructor);
			//...............Connection End................
		
			//.............statement Begin.............
			CtClass ctStatementIntf = classPool.get(Statement.class.getName());
			CtClass ctStatementWrapClass=classPool.get(ProxyStatementBase.class.getName());
			String ctStatementIntfProxyClassName ="cn.bee.dbcp.pool.ProxyStatement";
			CtClass ctStatementProxyImplClass = classPool.makeClass(ctStatementIntfProxyClassName,ctStatementWrapClass);
			ctStatementProxyImplClass.setInterfaces(new CtClass[]{ctStatementIntf});
			ctStatementProxyImplClass.setModifiers(Modifier.FINAL);
			ctStatementProxyImplClass.setModifiers(Modifier.PUBLIC);
			CtClass[] statementCreateParam = new CtClass[] {
					classPool.get("java.sql.Statement"),
					classPool.get("cn.bee.dbcp.pool.ProxyConnection")
					};
			subClassConstructor = new CtConstructor(statementCreateParam,ctStatementProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			body.delete(0, body.length());
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctStatementProxyImplClass.addConstructor(subClassConstructor);
		    //.............Statement Begin...............
			
			//............PreparedStatement Begin...............
			CtClass ctPsStatementIntf = classPool.get(PreparedStatement.class.getName());
			CtClass ctPStatementWrapClass=classPool.get(ProxyPsStatementBase.class.getName());
			String ctPsStatementIntfProxyClassName ="cn.bee.dbcp.pool.ProxyPsStatement";
			CtClass ctPsStatementProxyImplClass = classPool.makeClass(ctPsStatementIntfProxyClassName,ctPStatementWrapClass);
			ctPsStatementProxyImplClass.setInterfaces(new CtClass[]{ctPsStatementIntf});
			ctPsStatementProxyImplClass.setModifiers(Modifier.FINAL);
			ctPsStatementProxyImplClass.setModifiers(Modifier.PUBLIC);
			
			CtClass[] statementPsCreateParam = new CtClass[] {
					classPool.get("java.sql.PreparedStatement"),
					classPool.get("cn.bee.dbcp.pool.ProxyConnection"),
					classPool.get("boolean")};
			subClassConstructor = new CtConstructor(statementPsCreateParam,ctPsStatementProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			body.delete(0, body.length());
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctPsStatementProxyImplClass.addConstructor(subClassConstructor);
			//........PreparedStatement End..............
			
			//..............CallableStatement Begin.............
			CtClass ctCsStatementIntf = classPool.get(CallableStatement.class.getName());
			CtClass ctCStatementWrapClass=classPool.get(ProxyCsStatementBase.class.getName());
			String ctCsStatementIntfProxyClassName ="cn.bee.dbcp.pool.ProxyCsStatement";
			CtClass ctCsStatementProxyImplClass = classPool.makeClass(ctCsStatementIntfProxyClassName,ctCStatementWrapClass);
			ctCsStatementProxyImplClass.setInterfaces(new CtClass[]{ctCsStatementIntf});
			ctCsStatementProxyImplClass.setModifiers(Modifier.FINAL);
			ctCsStatementProxyImplClass.setModifiers(Modifier.PUBLIC);
			
			CtClass[] statementCsCreateParam = new CtClass[] {
					classPool.get("java.sql.CallableStatement"),
					classPool.get("cn.bee.dbcp.pool.ProxyConnection"),
					classPool.get("boolean")};
			subClassConstructor = new CtConstructor(statementCsCreateParam,ctCsStatementProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			
			body.delete(0, body.length());
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctCsStatementProxyImplClass.addConstructor(subClassConstructor);
			//...........CallableStatement End...............
		
			//..............DatabaseMetaData Begin.............
			CtClass ctDatabaseMetaDataIntf = classPool.get(DatabaseMetaData.class.getName());
			CtClass ctDatabaseMetaDataSuperClass = classPool.get(ProxyDatabaseMetaDataBase.class.getName());
			String cttDatabaseMetaDataIntfProxyClassName ="cn.bee.dbcp.pool.ProxyDatabaseMetaData";
			CtClass ctDatabaseMetaDataProxyImplClass = classPool.makeClass(cttDatabaseMetaDataIntfProxyClassName,ctDatabaseMetaDataSuperClass);
			ctDatabaseMetaDataProxyImplClass.setInterfaces(new CtClass[]{ctDatabaseMetaDataIntf});
			ctDatabaseMetaDataProxyImplClass.setModifiers(Modifier.FINAL);
			ctDatabaseMetaDataProxyImplClass.setModifiers(Modifier.PUBLIC);
			
			CtClass[] databaseMetaData = new CtClass[] {
					classPool.get("java.sql.DatabaseMetaData"),
					classPool.get("cn.bee.dbcp.pool.ProxyConnection"),
				};
			subClassConstructor = new CtConstructor(databaseMetaData,ctDatabaseMetaDataProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			body.delete(0, body.length());
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctDatabaseMetaDataProxyImplClass.addConstructor(subClassConstructor);
			//...........DatabaseMetaData End...............
		
			//............... Result Begin..................
			CtClass ctResultSetIntf = classPool.get(ResultSet.class.getName());
			CtClass ctResultSetSuperclass= classPool.get(ProxyResultSetBase.class.getName());
			String ctResultSetIntfProxyClassName ="cn.bee.dbcp.pool.ProxyResultSet";
			CtClass ctResultSetIntfProxyImplClass = classPool.makeClass(ctResultSetIntfProxyClassName,ctResultSetSuperclass);
			ctResultSetIntfProxyImplClass.setInterfaces(new CtClass[]{ctResultSetIntf});
			ctResultSetIntfProxyImplClass.setModifiers(Modifier.FINAL);
			ctResultSetIntfProxyImplClass.setModifiers(Modifier.PUBLIC);
			
			CtClass[] resultSetCreateParam = new CtClass[]{
					classPool.get("java.sql.ResultSet"),
					classPool.get("cn.bee.dbcp.pool.ProxyStatementTop")};
			subClassConstructor = new CtConstructor(resultSetCreateParam,ctResultSetIntfProxyImplClass);
			subClassConstructor.setModifiers(Modifier.PUBLIC);
			body.delete(0, body.length());
			body.append("{");
			body.append("super($$);");
			body.append("}");
			subClassConstructor.setBody(body.toString());
			ctResultSetIntfProxyImplClass.addConstructor(subClassConstructor);
			//............Result End...............
			
		  this.createProxyConnectionClass(classPool,ctConIntfProxyImplClass,ctConIntf,ctConSuperclass);
		  CtClass statementSuperClass= classPool.get(ProxyStatementBase.class.getName());
		  CtClass psStatementSuperClass= classPool.get(ProxyPsStatementBase.class.getName());
		  CtClass csStatementSuperClass= classPool.get(ProxyCsStatementBase.class.getName());
		  this.createProxyStatementClass(classPool,ctStatementProxyImplClass,ctStatementIntf,statementSuperClass);
		  this.createProxyPsStatementClass(classPool,ctPsStatementProxyImplClass,ctPsStatementIntf,psStatementSuperClass);
		  this.createProxyCsStatementClass(classPool,ctCsStatementProxyImplClass,ctCsStatementIntf,csStatementSuperClass);
		  this.createProxyDatabaseMetaDataClass(classPool,ctDatabaseMetaDataProxyImplClass,ctDatabaseMetaDataIntf,ctDatabaseMetaDataSuperClass);
		  this.createProxyResultSetClass(classPool,ctResultSetIntfProxyImplClass,ctResultSetIntf,ctResultSetSuperclass);

		  //............... FastConnectionPool Begin..................
 		  CtClass ctFastConnectionPoolClass = classPool.get(FastConnectionPool.class.getName());
		  CtClass[] ctcreateProxyConnectionParamTypes = new CtClass[] {
			 classPool.get("cn.bee.dbcp.pool.PooledConnection"),
			 classPool.get("cn.bee.dbcp.pool.Borrower"),
		  };
		  CtMethod createProxyConnectionMethod=ctFastConnectionPoolClass.getDeclaredMethod("createProxyConnection",ctcreateProxyConnectionParamTypes);
		  body.delete(0, body.length());
		  body.append("{");
		  body.append(" $2.setBorrowedConnection($1);");
		  body.append("	return $1.proxyConnCurInstance=new ProxyConnection($1);");
		  body.append("}");
		  createProxyConnectionMethod.setBody(body.toString());
		  //............... FastConnectionPool end..................
		  
		  return new CtClass[]{
					ctConIntfProxyImplClass,
					ctStatementProxyImplClass,
					ctPsStatementProxyImplClass,
					ctCsStatementProxyImplClass,
					ctDatabaseMetaDataProxyImplClass,
					ctResultSetIntfProxyImplClass,
					ctFastConnectionPoolClass};
		}catch(Throwable e){
			e.printStackTrace();
			throw new Exception(e);
		}
	}
	
	/**
	 * create connection proxy class, and add JDBC statement methods to it
	 * 
	 * @param classPool javassist class pool
	 * @param ctConIntfProxyClass connection implemented sub class will be generated
	 * @param ctConIntf connection interface in javassist class pool   
	 * @param ctConSuperClass super class extend by 'ctConIntfProxyClass' 
	 * @return proxy class base on connection interface
	 * @throws Exception some error occurred 
	 */
	private Class createProxyConnectionClass(ClassPool classPool,CtClass ctConIntfProxyClass,CtClass ctConIntf,CtClass ctConSuperClass)throws Exception{
		CtMethod[] ctSuperClassMethods = ctConSuperClass.getMethods();
		HashSet notNeedAddProxyMethods= new HashSet();
		for(int i=0,l=ctSuperClassMethods.length;i<l;i++){
			int modifiers=ctSuperClassMethods[i].getModifiers();
			if((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers)|| Modifier.isStatic(modifiers)|| Modifier.isNative(modifiers)){
				notNeedAddProxyMethods.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}
		
		LinkedList<CtMethod> linkedList = new LinkedList<CtMethod>();
		resolveInterfaceMethods(ctConIntf,linkedList,notNeedAddProxyMethods);
		
		
		CtClass ctStatementIntf = classPool.get(Statement.class.getName());
		CtClass ctPsStatementIntf = classPool.get(PreparedStatement.class.getName());
		CtClass ctCsStatementIntf = classPool.get(CallableStatement.class.getName());
		CtClass ctDatabaseMetaDataIntf = classPool.get(DatabaseMetaData.class.getName());
		
		StringBuilder methodBuffer = new StringBuilder();
		for(CtMethod ctMethod:linkedList){
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctConIntfProxyClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);
			
			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
			if (newCtMethodm.getReturnType() == ctStatementIntf) {
				methodBuffer.append("Statement stm=delegate.createStatement($$);");	
				methodBuffer.append("pooledConn.updateAccessTime();");	
				methodBuffer.append("return new ProxyStatement(stm,this);");	
			}else if(newCtMethodm.getReturnType() == ctPsStatementIntf){
				methodBuffer.append("if(stmCacheValid){");
				methodBuffer.append(  "Object key=new PsCacheKey($$);");
				methodBuffer.append("  PreparedStatement stm=stmCache.get(key);");
				methodBuffer.append("  if(stm==null){");
				methodBuffer.append("    stm=delegate.prepareStatement($$);");
				methodBuffer.append("    pooledConn.updateAccessTime();");	
				methodBuffer.append("    stmCache.put(key,stm);"); 
				methodBuffer.append("  }");
				methodBuffer.append("  return new ProxyPsStatement(stm,this,true);");	
				methodBuffer.append("}else{");
				methodBuffer.append(" PreparedStatement stm=delegate.prepareStatement($$);");
				methodBuffer.append(" pooledConn.updateAccessTime();");	
				methodBuffer.append(" return new ProxyPsStatement(stm,this,false);");	
				methodBuffer.append("}");
			}else if(newCtMethodm.getReturnType() == ctCsStatementIntf){
				methodBuffer.append("if(stmCacheValid){");
				methodBuffer.append(" Object key=new CsCacheKey($$);");
				methodBuffer.append(" CallableStatement stm=(CallableStatement)stmCache.get(key);");
				methodBuffer.append(" if(stm==null){");
				methodBuffer.append("   stm=delegate.prepareCall($$);");
				methodBuffer.append("   pooledConn.updateAccessTime();");	
				methodBuffer.append("   stmCache.put(key,stm);"); 
				methodBuffer.append(  "}");
				methodBuffer.append("  return new ProxyCsStatement(stm,this,true);");	
				methodBuffer.append("}else{");
				methodBuffer.append(" CallableStatement stm=delegate.prepareCall($$);");
				methodBuffer.append(" pooledConn.updateAccessTime();");	
				methodBuffer.append(" return new ProxyCsStatement(stm,this,false);");	
				methodBuffer.append("}");
				
			}else if (newCtMethodm.getReturnType() == ctDatabaseMetaDataIntf) {
				methodBuffer.append("return new ProxyDatabaseMetaData(delegate."+methodName+"($$),this);");
			}else if(methodName.equals("close")){
				methodBuffer.append("super."+methodName + "($$);");
			}else if (newCtMethodm.getReturnType() == CtClass.voidType){
				methodBuffer.append(" delegate." + methodName + "($$);");
				methodBuffer.append(" pooledConn.updateAccessTime();");	
			}else{
				methodBuffer.append(newCtMethodm.getReturnType().getName() +" re=delegate." + methodName + "($$);");
				methodBuffer.append(" pooledConn.updateAccessTime();");	
				methodBuffer.append(" return re;");	
			}
		     
			methodBuffer.append("}");
			newCtMethodm.setBody(methodBuffer.toString());
			ctConIntfProxyClass.addMethod(newCtMethodm);
			 
		}
		return ctConIntfProxyClass.toClass();
	}
	
	private Class createProxyStatementClass(ClassPool classPool, CtClass ctStatementProxyClass,CtClass ctStatementIntf, CtClass ctStatementSuperClass) throws Exception {
		CtMethod[] ctSuperClassMethods = ctStatementSuperClass.getMethods();
		HashSet superClassSignatureSet = new HashSet();
		for (int i = 0, l = ctSuperClassMethods.length; i < l; i++) {
			int modifiers = ctSuperClassMethods[i].getModifiers();
			if ((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) {
				superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}

		LinkedList<CtMethod> linkedList = new LinkedList();
		resolveInterfaceMethods(ctStatementIntf, linkedList, superClassSignatureSet);

		CtClass ctResultSetIntf=classPool.get(ResultSet.class.getName());
		StringBuilder methodBuffer = new StringBuilder();
		for (CtMethod ctMethod : linkedList) {
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctStatementProxyClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);

			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
			
			if (newCtMethodm.getReturnType() == ctResultSetIntf) {
				methodBuffer.append("return new ProxyResultSet(delegate."+methodName+"($$),this);");	
			}else if (methodName.equals("close")){
				methodBuffer.append("super."+methodName +"($$);");
			}else{
				if (newCtMethodm.getReturnType() == CtClass.voidType){
					methodBuffer.append("delegate."+methodName + "($$);");
				}else{
					methodBuffer.append("return delegate."+methodName + "($$);");
				}
			}
			methodBuffer.append("}");
			newCtMethodm.setBody(methodBuffer.toString());
			ctStatementProxyClass.addMethod(newCtMethodm);

		}
		return ctStatementProxyClass.toClass();
	}
	
 
	private Class createProxyPsStatementClass(ClassPool classPool,CtClass ctPsStatementProxyClass,CtClass ctPsStatementIntf,CtClass ctPsStatementSuperClass)throws Exception{
		CtMethod[] ctSuperClassMethods = ctPsStatementSuperClass.getMethods();
		HashSet superClassSignatureSet= new HashSet();
		for(int i=0,l=ctSuperClassMethods.length;i<l;i++){
			int modifiers=ctSuperClassMethods[i].getModifiers();
			if((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers)|| Modifier.isStatic(modifiers)|| Modifier.isNative(modifiers)){
				superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}
	
		LinkedList<CtMethod> linkedList = new LinkedList();
		resolveInterfaceMethods(ctPsStatementIntf,linkedList,superClassSignatureSet);
		CtClass ctResultSetIntf=classPool.get(ResultSet.class.getName());
		
		StringBuilder methodBuffer = new StringBuilder();
		for(CtMethod ctMethod:linkedList){
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctPsStatementProxyClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);
			
			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
			
			if (newCtMethodm.getReturnType() == ctResultSetIntf) {
				methodBuffer.append("return new ProxyResultSet(delegate."+methodName+"($$),this);"); 
			}else if (methodName.equals("close")) {
				methodBuffer.append("super."+methodName+"($$);");
			}else{
				if(newCtMethodm.getReturnType() == CtClass.voidType){
					methodBuffer.append("delegate." + methodName + "($$);");
				}else{
					methodBuffer.append("return delegate."+methodName + "($$);");
		
				}
			}
			methodBuffer.append("}");
			
			newCtMethodm.setBody(methodBuffer.toString());
			ctPsStatementProxyClass.addMethod(newCtMethodm);
			 
		}
		return ctPsStatementProxyClass.toClass();
	}
	
	private Class createProxyCsStatementClass(ClassPool classPool,CtClass ctCsStatementProxyClass,CtClass ctCsStatementIntf,CtClass ctCsStatementSuperClass)throws Exception{
		CtMethod[] ctSuperClassMethods = ctCsStatementSuperClass.getMethods();
		HashSet superClassSignatureSet= new HashSet();
		for(int i=0,l=ctSuperClassMethods.length;i<l;i++){
			int modifiers=ctSuperClassMethods[i].getModifiers();
			if((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers)|| Modifier.isStatic(modifiers)|| Modifier.isNative(modifiers)){
				superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}
		
		LinkedList<CtMethod> linkedList = new LinkedList();
		resolveInterfaceMethods(ctCsStatementIntf,linkedList,superClassSignatureSet);
		
		CtClass ctResultSetIntf=classPool.get(ResultSet.class.getName());
		StringBuilder methodBuffer = new StringBuilder();
		for(CtMethod ctMethod:linkedList){
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctCsStatementProxyClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);
			
			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
 
			if (newCtMethodm.getReturnType() == ctResultSetIntf) {
				methodBuffer.append("return new ProxyResultSet(delegate."+methodName+"($$),this);"); 
			}else if (methodName.equals("close")) {
				methodBuffer.append("super."+methodName+"($$);");
			}else{
				if(newCtMethodm.getReturnType() == CtClass.voidType){
					methodBuffer.append("delegate." + methodName + "($$);");
				}else{
					methodBuffer.append("return delegate."+methodName + "($$);");	
				}
			}
			methodBuffer.append("}");
			newCtMethodm.setBody(methodBuffer.toString());
			ctCsStatementProxyClass.addMethod(newCtMethodm);
			 
		}
		return ctCsStatementProxyClass.toClass();
	}
	
	
	//ctDatabaseMetaDataProxyImplClass,ctDatabaseMetaDataIntf,ctDatabaseMetaDataSuperClass
	private Class createProxyDatabaseMetaDataClass(ClassPool classPool,CtClass ctDatabaseMetaDataProxyImplClass,CtClass ctDatabaseMetaDataIntf,CtClass ctDatabaseMetaDataSuperClass)throws Exception{
		CtMethod[] ctSuperClassMethods = ctDatabaseMetaDataSuperClass.getMethods();
		HashSet superClassSignatureSet= new HashSet();
		for(int i=0,l=ctSuperClassMethods.length;i<l;i++){
			int modifiers=ctSuperClassMethods[i].getModifiers();
			if((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers)|| Modifier.isStatic(modifiers)|| Modifier.isNative(modifiers)){
				superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}
		
		LinkedList<CtMethod> linkedList = new LinkedList();
		resolveInterfaceMethods(ctDatabaseMetaDataIntf,linkedList,superClassSignatureSet);
		CtClass ctResultSetIntf=classPool.get(ResultSet.class.getName());
		
		StringBuilder methodBuffer = new StringBuilder();
		for(CtMethod ctMethod:linkedList){
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctDatabaseMetaDataProxyImplClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);

			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
			
			if (newCtMethodm.getReturnType() == ctResultSetIntf) {
				methodBuffer.append("return new ProxyResultSet(delegate."+methodName+"($$),null);");	 
			} else if (newCtMethodm.getReturnType() == CtClass.voidType) {
				methodBuffer.append("delegate." + methodName+"($$);");
			} else {
				methodBuffer.append("return delegate."+methodName+"($$);");
			}
			
			methodBuffer.append("}");
			newCtMethodm.setBody(methodBuffer.toString());
			ctDatabaseMetaDataProxyImplClass.addMethod(newCtMethodm); 
		}
		return ctDatabaseMetaDataProxyImplClass.toClass();
	}
	
	private Class createProxyResultSetClass(ClassPool classPool,CtClass ctResultSetIntfProxyClass,CtClass ctResultSetIntf,CtClass ctResultSetIntfSuperClass)throws Exception{
		CtMethod[] ctSuperClassMethods = ctResultSetIntfSuperClass.getMethods();
		HashSet superClassSignatureSet= new HashSet();
		for(int i=0,l=ctSuperClassMethods.length;i<l;i++){
			int modifiers=ctSuperClassMethods[i].getModifiers();
			if((!Modifier.isAbstract(modifiers) && (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers)))
					|| Modifier.isFinal(modifiers)|| Modifier.isStatic(modifiers)|| Modifier.isNative(modifiers)){
				superClassSignatureSet.add(ctSuperClassMethods[i].getName() + ctSuperClassMethods[i].getSignature());
			}
		}
		
		LinkedList<CtMethod> linkedList = new LinkedList();
		resolveInterfaceMethods(ctResultSetIntf,linkedList,superClassSignatureSet);
		StringBuilder methodBuffer = new StringBuilder();
		
		for(CtMethod ctMethod:linkedList){
			String methodName = ctMethod.getName();
			CtMethod newCtMethodm = CtNewMethod.copy(ctMethod, ctResultSetIntfProxyClass, null);
			newCtMethodm.setModifiers(Modifier.PUBLIC);
			
			methodBuffer.delete(0, methodBuffer.length());
			methodBuffer.append("{");
			methodBuffer.append("checkClose();");
			if (methodName.equals("close")) {
				methodBuffer.append("super." + methodName + "($$);");
			} else {
				if(newCtMethodm.getReturnType() == CtClass.voidType){
					methodBuffer.append("delegate." + methodName + "($$);");
				}else{
					methodBuffer.append("return delegate."+methodName+"($$);");
				}
			}
			methodBuffer.append("}");
			newCtMethodm.setBody(methodBuffer.toString());
			ctResultSetIntfProxyClass.addMethod(newCtMethodm);
		}		   
		return ctResultSetIntfProxyClass.toClass();
	}
	
	private void resolveInterfaceMethods(CtClass interfaceClass,LinkedList linkedList,HashSet exitSignatureSet)throws Exception{
		CtMethod[] ctMethods = interfaceClass.getDeclaredMethods();
		for(int i=0;i<ctMethods.length;i++){
			int modifiers=ctMethods[i].getModifiers();
			String signature = ctMethods[i].getName()+ctMethods[i].getSignature();
			if(Modifier.isAbstract(modifiers) 
					&& (Modifier.isPublic(modifiers)||Modifier.isProtected(modifiers))
					&& !Modifier.isStatic(modifiers) 
					&& !Modifier.isFinal(modifiers) 
					&& !exitSignatureSet.contains(signature)){
				
				linkedList.add(ctMethods[i]);
				exitSignatureSet.add(signature);
			}
		}
		
		CtClass[] superInterfaces=interfaceClass.getInterfaces();
		for(int i=0;i<superInterfaces.length;i++){
			resolveInterfaceMethods(superInterfaces[i],linkedList,exitSignatureSet);
		}
	}
}
