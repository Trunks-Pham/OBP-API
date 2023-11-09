package code.api.cache

import code.api.Constant._
import code.api.cache.Redis.jedis
import code.util.Helper.MdcLoggable
import com.softwaremill.macmemo.{Cache, MemoCacheBuilder, MemoizeParams}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.postfixOps
import scala.reflect.runtime.universe._
object Caching extends MdcLoggable {

  def memoizeSyncWithProvider[A](cacheKey: Option[String])(ttl: Duration)(f: => A)(implicit m: Manifest[A]): A = {
    (cacheKey, ttl) match {
      case (_, t) if t == Duration.Zero  => // Just forwarding a call
        f
      case (Some(_), _) if !Redis.isRedisAvailable() => // Redis is NOT available. Warn via log file and forward the call
        f
      case (Some(_), _) => // Caching a call
        Redis.memoizeSyncWithRedis(cacheKey)(ttl)(f)
      case _  => // Just forwarding a call
        f
    }

  }

  def memoizeWithProvider[A](cacheKey: Option[String])(ttl: Duration)(f: => Future[A])(implicit m: Manifest[A]): Future[A] = {
    (cacheKey, ttl) match {
      case (_, t) if t == Duration.Zero  => // Just forwarding a call
        f
      case (Some(_), _) if !Redis.isRedisAvailable() => // Redis is NOT available. Warn via log file and forward the call
        f
      case (Some(_), _) => // Caching a call
        Redis.memoizeWithRedis(cacheKey)(ttl)(f)
      case _  => // Just forwarding a call
        f
    }

  }

  /**
   * the default MemoCacheBuilder for annotation OBPMemoize
   *
   * e.g:
   *{{{
   *   import Caching._
   *
   *     @OBPMemoize(ttl = 2 hours, maxSize = 111)
   *     def hello(name: String, age: Int): Future[String] = ???
   *}}}
   */
  implicit object OBPCacheBuilder extends  MemoCacheBuilder {
    override def build[V : TypeTag : Manifest](bucketId: String, params: MemoizeParams): Cache[V] = new Cache[V] {
      val ttl = params.expiresAfterMillis
      var isFuture = implicitly[TypeTag[V]].tpe <:< typeOf[Future[_]]
      var fixedReturnType = false

      override def get(key: List[Any], compute: => V): V = {
        val cacheKey = bucketId + "_" + key.mkString("_")
        if(isFuture) {
          val result = memoizeWithProvider(Some(cacheKey))(ttl)(compute.asInstanceOf[Future[Any]])
          result.asInstanceOf[V]
        } else if(implicitly[TypeTag[V]].tpe =:= typeOf[Any] && !fixedReturnType) {
          val result = compute
          isFuture = result.isInstanceOf[Future[_]]
          fixedReturnType = true
          this.get(key, result)
        } else {
          val result = memoizeSyncWithProvider(Some(cacheKey))(ttl)(compute)
          if(result.isInstanceOf[Future[_]]) {
            isFuture = true
          }
          result
        }
      }
    }
  }

  def getLocalisedResourceDocCache(key: String) = {
    if(CREATE_LOCALISED_RESOURCE_DOC_JSON_TTL!=0 && Redis.isRedisAvailable())
      jedis.get(LOCALISED_RESOURCE_DOC_PREFIX + key) // if the key is not existing, jedis will return null
    else 
      null
  }
  
  def setLocalisedResourceDocCache(key:String, value: String)={
    if (CREATE_LOCALISED_RESOURCE_DOC_JSON_TTL!=0 && Redis.isRedisAvailable())
      jedis.setex(LOCALISED_RESOURCE_DOC_PREFIX+key, CREATE_LOCALISED_RESOURCE_DOC_JSON_TTL, value)
    else
      null
  }

  def getDynamicResourceDocCache(key: String) = {
    if (GET_DYNAMIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.get(DYNAMIC_RESOURCE_DOC_CACHE_KEY_PREFIX + key)
    else
      null
  }
  
  def setDynamicResourceDocCache(key:String, value: String)={
    if (GET_DYNAMIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.setex(DYNAMIC_RESOURCE_DOC_CACHE_KEY_PREFIX+key,GET_DYNAMIC_RESOURCE_DOCS_TTL,value)
    else
      null
  }

  def getStaticResourceDocCache(key: String) = {
    if (GET_STATIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.get(STATIC_RESOURCE_DOC_CACHE_KEY_PREFIX + key)
    else
      null
  }
  
  def setStaticResourceDocCache(key:String, value: String)={
    if (GET_STATIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.setex(STATIC_RESOURCE_DOC_CACHE_KEY_PREFIX+key,GET_STATIC_RESOURCE_DOCS_TTL,value)
    else
      null
  }

  def getAllResourceDocCache(key: String) = {
    if (GET_DYNAMIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.get(ALL_RESOURCE_DOC_CACHE_KEY_PREFIX + key) // null
    else
      null
  }
  
  def setAllResourceDocCache(key:String, value: String)={
    if (GET_DYNAMIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.setex(ALL_RESOURCE_DOC_CACHE_KEY_PREFIX+key,GET_DYNAMIC_RESOURCE_DOCS_TTL,value)
    else
      null
  }

  def getStaticSwaggerDocCache(key: String) = {
    if (GET_STATIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.get(STATIC_SWAGGER_DOC_CACHE_KEY_PREFIX + key)
    else
      null
  }
  
  def setStaticSwaggerDocCache(key:String, value: String)={
    if (GET_STATIC_RESOURCE_DOCS_TTL!=0 && Redis.isRedisAvailable())
      jedis.setex(STATIC_SWAGGER_DOC_CACHE_KEY_PREFIX+key,GET_STATIC_RESOURCE_DOCS_TTL,value)
    else
      null
  }
  
}
