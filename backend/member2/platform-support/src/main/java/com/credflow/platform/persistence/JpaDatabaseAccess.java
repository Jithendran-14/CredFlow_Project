package com.credflow.platform.persistence;
import jakarta.persistence.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.hibernate.Session;
import org.springframework.dao.*;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.transaction.annotation.Transactional;
/** JPA owns the persistence context/transaction. Native SQL preserves Oracle locks and PL/SQL. */
@org.springframework.stereotype.Repository
@Transactional(noRollbackFor=DuplicateKeyException.class)
public class JpaDatabaseAccess implements DatabaseAccess {
 private final EntityManager em;
 public JpaDatabaseAccess(EntityManager em){this.em=em;}
 private Query bind(String sql,Object...args) {
  var text=new StringBuilder();int position=1;
  for(char c:sql.toCharArray()){text.append(c);if(c=='?')text.append(position++);}
  Query query=em.createNativeQuery(text.toString(),Tuple.class);
  for(int i=0;i<args.length;i++)query.setParameter(i+1,args[i]);return query;
 }
 @Override public int update(String sql,Object...args) {
  // Oracle leaves a transaction usable after a unique-key violation. Catch it before Hibernate
  // can mark the whole transaction rollback-only; inbox/idempotency replay depends on this.
  String normalized=sql.stripLeading().toUpperCase(Locale.ROOT);
  if(List.of("INSERT INTO EVENT_INBOX(","INSERT INTO FINANCIAL_IMPORT_BATCH(","INSERT INTO ELIGIBILITY_EVALUATION(","INSERT INTO ELIGIBILITY_OPERATION(").stream().anyMatch(normalized::startsWith)) {
   record Write(int count,SQLException error) {}
   Write result=em.unwrap(Session.class).doReturningWork(connection->{
    try(var statement=connection.prepareStatement(sql)) {
     for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
     return new Write(statement.executeUpdate(),null);
    } catch(SQLException ex){return new Write(0,ex);}
   });
   if(result.error()!=null) {
    SQLException ex=result.error();
    if(ex.getErrorCode()==1 || "23505".equals(ex.getSQLState()))throw new DuplicateKeyException("Duplicate persistence key",ex);
    DataAccessException translated=new SQLErrorCodeSQLExceptionTranslator("Oracle").translate("Native insert",sql,ex);
    throw translated==null?new DataIntegrityViolationException("Native insert failed",ex):translated;
   }
   return result.count();
  }
  // Mutation queries must not request a Tuple result mapping.
  var text=new StringBuilder();int position=1;
  for(char c:sql.toCharArray()){text.append(c);if(c=='?')text.append(position++);}
  Query q=em.createNativeQuery(text.toString());for(int i=0;i<args.length;i++)q.setParameter(i+1,args[i]);return q.executeUpdate();
 }
 private static Object materialize(Object value)throws SQLException {
  if(value instanceof oracle.sql.TIMESTAMPTZ timestamp)return timestamp.offsetDateTimeValue();
  if(value instanceof Clob clob)return clob.getSubString(1,Math.toIntExact(clob.length()));
  return value;
 }
 @Override public <T>List<T> query(String sql,Mapper<T> mapper,Object...args) {
  List<?> tuples=bind(sql,args).getResultList();List<T> result=new ArrayList<>();int index=0;
  try {
   for(Object item:tuples){Tuple tuple=(Tuple)item;
    var names=new TreeMap<String,Object>(String.CASE_INSENSITIVE_ORDER);
    for(var element:tuple.getElements())names.put(element.getAlias(),materialize(tuple.get(element)));
    List<Object> values=new ArrayList<>();for(Object v:tuple.toArray())values.add(materialize(v));
    Row row=key->key instanceof Integer n?values.get(n-1):names.get(key.toString());
    result.add(mapper.map(row,index++));
   }
   return result;
  }catch(SQLException ex){throw new DataRetrievalFailureException("Cannot read database row",ex);}
 }
 @Override public List<Map<String,Object>> queryForList(String sql,Object...args) {
  List<?> tuples=bind(sql,args).getResultList();var result=new ArrayList<Map<String,Object>>();
  try {
   for(Object item:tuples){Tuple t=(Tuple)item;var row=new TreeMap<String,Object>(String.CASE_INSENSITIVE_ORDER);
    for(var e:t.getElements())row.put(e.getAlias().toUpperCase(Locale.ROOT),materialize(t.get(e)));result.add(row);}
   return result;
  }catch(SQLException ex){throw new DataRetrievalFailureException("Cannot read database row",ex);}
 }
 @Override public String buildSnapshot(String app,int revision,LocalDate asOf,String batch) {
  StoredProcedureQuery q=em.createStoredProcedureQuery("CF_FINANCIAL_SUMMARY_PKG.BUILD_SNAPSHOT");
  q.registerStoredProcedureParameter(1,String.class,ParameterMode.IN);
  q.registerStoredProcedureParameter(2,Integer.class,ParameterMode.IN);
  q.registerStoredProcedureParameter(3,java.sql.Date.class,ParameterMode.IN);
  q.registerStoredProcedureParameter(4,String.class,ParameterMode.IN);
  q.registerStoredProcedureParameter(5,String.class,ParameterMode.OUT);
  q.setParameter(1,app);q.setParameter(2,revision);q.setParameter(3,java.sql.Date.valueOf(asOf));q.setParameter(4,batch);
  q.execute();return (String)q.getOutputParameterValue(5);
 }
}
