package de.kp.spark.arules.actor
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
 * 
 * This file is part of the Spark-ARULES project
 * (https://github.com/skrusche63/spark-arules).
 * 
 * Spark-ARULES is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * Spark-ARULES is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with
 * Spark-ARULES. 
 * 
 * If not, see <http://www.gnu.org/licenses/>.
 */

import akka.actor.Actor

import org.apache.spark.rdd.RDD
import org.apache.hadoop.conf.{Configuration => HConf}

import de.kp.spark.arules.{Configuration,Rule,TopKNR}
import de.kp.spark.arules.source.{ElasticSource,FileSource,JdbcSource}

import de.kp.spark.arules.model._
import de.kp.spark.arules.util.{JobCache,RuleCache}

class TopKNRActor(jobConf:JobConf) extends Actor with SparkActor {
  
  /* Create Spark context */
  private val sc = createCtxLocal("TopKNRActor",Configuration.spark)
  
  private val uid = jobConf.get("uid").get.asInstanceOf[String]     
  JobCache.add(uid,ARulesStatus.STARTED)

  private val params = parameters()

  private val response = if (params == null) {
    val message = ARulesMessages.TOP_KNR_MISSING_PARAMETERS(uid)
    new ARulesResponse(uid,Some(message),None,None,ARulesStatus.FAILURE)
  
  } else {
     val message = ARulesMessages.TOP_KNR_MINING_STARTED(uid)
     new ARulesResponse(uid,Some(message),None,None,ARulesStatus.STARTED)
    
  }

  def receive = {
    
    /*
     * Retrieve Top-KNR association rules from an appropriate 
     * search index from Elasticsearch
     */     
    case req:ElasticRequest => {

      /* Send response to originator of request */
      sender ! response
          
      if (params != null) {

        try {
          
          /* Retrieve data from Elasticsearch */    
          val source = new ElasticSource(sc)
          val dataset = source.connect()

          JobCache.add(uid,ARulesStatus.DATASET)
          
          val (k,minconf,delta) = params     
          findRules(dataset,k,minconf,delta)

        } catch {
          case e:Exception => JobCache.add(uid,ARulesStatus.FAILURE)          
        }
      
      }
      
      sc.stop
      context.stop(self)
      
    }
    
    /*
     * Retrieve Top-KNR association rules from an appropriate 
     * file from the (HDFS) file system
     */
    case req:FileRequest => {

      /* Send response to originator of request */
      sender ! response
          
      if (params != null) {

        try {
    
          /* Retrieve data from the file system */
          val source = new FileSource(sc)
          val dataset = source.connect()

          JobCache.add(uid,ARulesStatus.DATASET)

          val (k,minconf,delta) = params          
          findRules(dataset,k,minconf,delta)

        } catch {
          case e:Exception => JobCache.add(uid,ARulesStatus.FAILURE)
        }
        
      }
      
      sc.stop
      context.stop(self)
      
    }
    /*
     * Retrieve Top-KNR association rules from an appropriate
     * table from a JDBC database 
     */
    case req:JdbcRequest => {

      /* Send response to originator of request */
      sender ! response
          
      if (params != null) {

        try {
    
          val source = new JdbcSource(sc)
          val dataset = source.connect(Map("site" -> req.site, "query" -> req.query))

          JobCache.add(uid,ARulesStatus.DATASET)

          val (k,minconf,delta) = params     
          findRules(dataset,k,minconf,delta)

        } catch {
          case e:Exception => JobCache.add(uid,ARulesStatus.FAILURE)
        }
        
      }
      
      sc.stop
      context.stop(self)
      
    }
    
    case _ => {}
    
  }
  
  private def findRules(dataset:RDD[(Int,Array[Int])],k:Int,minconf:Double,delta:Int) {
          
    val rules = TopKNR.extractRules(dataset,k,minconf,delta).map(rule => {
     
      val antecedent = rule.getItemset1().toList
      val consequent = rule.getItemset2().toList

      val support    = rule.getAbsoluteSupport()
      val confidence = rule.getConfidence()
	
      new Rule(antecedent,consequent,support,confidence)
            
    })
          
    /* Put rules to RuleCache */
    RuleCache.add(uid,rules)
          
    /* Update JobCache */
    JobCache.add(uid,ARulesStatus.FINISHED)
    
  }
  
  private def parameters():(Int,Double,Int) = {
      
    try {
      val k = jobConf.get("k").get.asInstanceOf[Int]
      val minconf = jobConf.get("minconf").get.asInstanceOf[Double]
        
      val delta = jobConf.get("delta").get.asInstanceOf[Int]
      return (k,minconf,delta)
        
    } catch {
      case e:Exception => {
         return null          
      }
    }
    
  }
  
}