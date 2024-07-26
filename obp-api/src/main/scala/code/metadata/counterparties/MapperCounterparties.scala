package code.metadata.counterparties

import java.util.UUID.randomUUID
import java.util.{Date, UUID}

import code.api.cache.Caching
import code.api.util.{APIUtil, CallContext}
import code.api.util.APIUtil.getSecondsCache
import code.model._
import code.model.dataAccess.ResourceUser
import code.users.Users
import code.util.Helper.MdcLoggable
import code.util._
import com.google.common.cache.CacheBuilder
import com.openbankproject.commons.model._
import com.tesobe.CacheKeyFromArguments
import net.liftweb.common.{Box, Full}
import net.liftweb.mapper.{By, MappedString, _}
import net.liftweb.util.Helpers.tryo

import scala.concurrent.duration._

// For now, there are two Counterparties: one is used for CreateCounterParty.Counterparty, the other is for getTransactions.Counterparty.
// 1st is created by app explicitly, when use `CreateCounterParty` endpoint. This will be stored in database .
// 2nd is generated by obp implicitly, when use `getTransactions` endpoint. This will not be stored in database, but we create the CounterpartyMetadata for it. And the CounterpartyMetadata is in database. 
// They are relevant somehow, but they are different data for now. Both data can be get by the following `MapperCounterparties` object. 
object MapperCounterparties extends Counterparties with MdcLoggable {

  // TODO Rewrite caching function
  val MetadataTTL = 0 // getSecondsCache("getOrCreateMetadata")
  
  override def getOrCreateMetadata(bankId: BankId, accountId: AccountId, counterpartyId: String, counterpartyName:String): Box[CounterpartyMetadata] = {
    /**
      * Please note that "var cacheKey = (randomUUID().toString, randomUUID().toString, randomUUID().toString)"
      * is just a temporary value field with UUID values in order to prevent any ambiguity.
      * The real value will be assigned by Macro during compile time at this line of a code:
      * https://github.com/OpenBankProject/scala-macros/blob/master/macros/src/main/scala/com/tesobe/CacheKeyFromArgumentsMacro.scala#L49
      */
    var cacheKey = (randomUUID().toString, randomUUID().toString, randomUUID().toString)
    CacheKeyFromArguments.buildCacheKey {
      Caching.memoizeSyncWithProvider(Some(cacheKey.toString()))(MetadataTTL second) {

        /**
          * Generates a new alias name that is guaranteed not to collide with any existing public alias names
          * for the account in question
          */
        def newPublicAliasName(): String = {
          val firstAliasAttempt = "ALIAS_" + UUID.randomUUID.toString.toUpperCase.take(6)

          val counterpartyMetadatasPublicAlias = MappedCounterpartyMetadata
            .findAll(
              By(MappedCounterpartyMetadata.thisBankId, bankId.value),
              By(MappedCounterpartyMetadata.thisAccountId, accountId.value))
            .map(_.addPublicAlias)

          def isDuplicate(publicAlias: String) = counterpartyMetadatasPublicAlias.contains(publicAlias)

          /**
            * Appends things to @publicAlias until it a unique public alias name within @account
            */
          def appendUntilUnique(publicAlias: String): String = {
            val newAlias = publicAlias + UUID.randomUUID.toString.toUpperCase.take(1)
            // Recursive call.
            if (isDuplicate(newAlias)) appendUntilUnique(newAlias)
            else newAlias
          }

          if (isDuplicate(firstAliasAttempt)) appendUntilUnique(firstAliasAttempt)
          else firstAliasAttempt
        }

        def findMappedCounterpartyMetadataById(counterpartyId: String) = MappedCounterpartyMetadata.find(By(MappedCounterpartyMetadata.counterpartyId, counterpartyId))

        findMappedCounterpartyMetadataById(counterpartyId) match {
          case Full(e) =>
            logger.debug(s"getOrCreateMetadata--Get MappedCounterpartyMetadata counterpartyId($counterpartyId)")
            Full(e)
          // Create it!
          case _ => {
            logger.debug(s"getOrCreateMetadata--Create MappedCounterpartyMetadata counterpartyId($counterpartyId)")
            // Store a record that contains counterparty information from the perspective of an account at a bank
            Full(MappedCounterpartyMetadata.create
              // Core info
              .counterpartyId(counterpartyId)
              .thisBankId(bankId.value)
              .thisAccountId(accountId.value)
              .counterpartyName(counterpartyName)
              .publicAlias(newPublicAliasName()) // The public alias this account gives to the counterparty.
              .saveMe)
          }
        }
      }
    }
  }

  // Get all counterparty metadata for a single OBP account
  override def getMetadatas(originalPartyBankId: BankId, originalPartyAccountId: AccountId): List[CounterpartyMetadata] = {
    MappedCounterpartyMetadata.findAll(
      By(MappedCounterpartyMetadata.thisBankId, originalPartyBankId.value),
      By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value)
    )
  }

  override def getMetadata(originalPartyBankId: BankId, originalPartyAccountId: AccountId, counterpartyMetadataId: String): Box[CounterpartyMetadata] = {
    /**
     * This particular implementation requires the metadata id to be the same as the otherParty (OtherBankAccount) id
     */
    MappedCounterpartyMetadata.find(
      By(MappedCounterpartyMetadata.thisBankId, originalPartyBankId.value),
      By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value),
      By(MappedCounterpartyMetadata.counterpartyId, counterpartyMetadataId)
    )
  }

  override def deleteMetadata(originalPartyBankId: BankId, originalPartyAccountId: AccountId, counterpartyMetadataId: String): Box[Boolean] = {
    MappedCounterpartyMetadata.find(
      By(MappedCounterpartyMetadata.thisBankId, originalPartyBankId.value),
      By(MappedCounterpartyMetadata.thisAccountId, originalPartyAccountId.value),
      By(MappedCounterpartyMetadata.counterpartyId, counterpartyMetadataId)
    ).map(_.delete_!)
  }

  def addMetadata(bankId: BankId, accountId : AccountId): Box[CounterpartyMetadata] = {
    Full(
    MappedCounterpartyMetadata.create
      .thisBankId(bankId.value)
      .thisAccountId(accountId.value)
      .saveMe
    )
  }


  override def getCounterparty(counterpartyId : String): Box[CounterpartyTrait] = {
    MappedCounterparty.find(By(MappedCounterparty.mCounterPartyId, counterpartyId))
  }

  override def deleteCounterparty(counterpartyId : String): Box[Boolean] = {
    MappedCounterparty.find(By(MappedCounterparty.mCounterPartyId, counterpartyId)).map(_.delete_!)
  }
  
  //TODO, here has a problem, MappedCounterparty has no unique constrain on IBan. But we get Counterparty By Iban. For now, we do not support update Counterpary endpoint. Here we only return the latest record.
  override def getCounterpartyByIban(iban : String)= {
    MappedCounterparty.find(
      By(MappedCounterparty.mOtherAccountSecondaryRoutingAddress, iban),
      OrderBy(MappedCounterparty.id, Descending) //Always use the latest record. 
    )
  }

  def getCounterpartyByIbanAndBankAccountId(iban : String, bankId: BankId, accountId: AccountId) = {
    MappedCounterparty.find(
      By(MappedCounterparty.mOtherAccountSecondaryRoutingAddress, iban),
      By(MappedCounterparty.mThisBankId, bankId.value),
      By(MappedCounterparty.mThisAccountId, accountId.value)
    )
  }

  override def getCounterpartyByRoutings(
    otherBankRoutingScheme: String,
    otherBankRoutingAddress: String,
    otherBranchRoutingScheme: String,
    otherBranchRoutingAddress: String,
    otherAccountRoutingScheme: String,
    otherAccountRoutingAddress: String
  ): Box[CounterpartyTrait] = {
    MappedCounterparty.find(
      By(MappedCounterparty.mOtherBankRoutingScheme,otherBankRoutingScheme),
      By(MappedCounterparty.mOtherBankRoutingAddress,otherBankRoutingAddress),
      By(MappedCounterparty.mOtherBranchRoutingScheme,otherBranchRoutingScheme),
      By(MappedCounterparty.mOtherBranchRoutingAddress,otherBranchRoutingAddress),
      By(MappedCounterparty.mOtherAccountRoutingScheme,otherAccountRoutingScheme),
      By(MappedCounterparty.mOtherAccountRoutingAddress,otherAccountRoutingAddress),
    )
  }

  override def getCounterpartyBySecondaryRouting(
    otherAccountSecondaryRoutingScheme: String,
    otherAccountSecondaryRoutingAddress: String
  ): Box[CounterpartyTrait] ={
    MappedCounterparty.find(
      By(MappedCounterparty.mOtherAccountSecondaryRoutingScheme, otherAccountSecondaryRoutingScheme),
      By(MappedCounterparty.mOtherAccountSecondaryRoutingAddress, otherAccountSecondaryRoutingAddress),
    )
  }
  
  
  
  override def getCounterparties(thisBankId: BankId, thisAccountId: AccountId, viewId: ViewId): Box[List[CounterpartyTrait]] = {
    Full(MappedCounterparty.findAll(By(MappedCounterparty.mThisAccountId, thisAccountId.value),
      By(MappedCounterparty.mThisBankId, thisBankId.value),
      By(MappedCounterparty.mThisViewId, viewId.value)))
  }

  override def createCounterparty(
                                  createdByUserId: String,
                                  thisBankId: String,
                                  thisAccountId : String,
                                  thisViewId : String,
                                  name: String,
                                  otherAccountRoutingScheme : String,
                                  otherAccountRoutingAddress : String,
                                  otherBankRoutingScheme : String,
                                  otherBankRoutingAddress : String,
                                  otherBranchRoutingScheme: String,
                                  otherBranchRoutingAddress: String,
                                  isBeneficiary: Boolean,
                                  otherAccountSecondaryRoutingScheme: String,
                                  otherAccountSecondaryRoutingAddress: String,
                                  description: String,
                                  currency: String,
                                  bespoke: List[CounterpartyBespoke]
                                 ): Box[CounterpartyTrait] = {
    
    val mappedCounterparty = MappedCounterparty.create
      .mCounterPartyId(APIUtil.createExplicitCounterpartyId) //We create the Counterparty_Id here, it means, it will be create in each connector.
      .mName(name)
      .mCreatedByUserId(createdByUserId)
      .mThisBankId(thisBankId)
      .mThisAccountId(thisAccountId)
      .mThisViewId(thisViewId)
      .mOtherAccountRoutingScheme(otherAccountRoutingScheme)
      .mOtherAccountRoutingAddress(otherAccountRoutingAddress)
      .mOtherBankRoutingScheme(otherBankRoutingScheme)
      .mOtherBankRoutingAddress(otherBankRoutingAddress)
      .mOtherBranchRoutingAddress(otherBranchRoutingAddress)
      .mOtherBranchRoutingScheme(otherBranchRoutingScheme)
      .mIsBeneficiary(isBeneficiary)
      .mDescription(description)
      .mCurrency(currency)
      .mOtherAccountSecondaryRoutingScheme(otherAccountSecondaryRoutingScheme)
      .mOtherAccountSecondaryRoutingAddress(otherAccountSecondaryRoutingAddress)
      .saveMe()
  
    // This is especially for OneToMany table, to save a List to database.
    CounterpartyBespokes.counterpartyBespokers.vend
      .createCounterpartyBespokes(mappedCounterparty.id.get, bespoke)
      .map(mappedBespoke =>mappedCounterparty.mBespoke += mappedBespoke)
    
    Some(mappedCounterparty)
    
  }

 override def checkCounterpartyExists(
                               name: String,
                               thisBankId: String,
                               thisAccountId: String,
                               thisViewId: String
                             ): Box[CounterpartyTrait] = {
 MappedCounterparty.find(
     By(MappedCounterparty.mName, name),
     By(MappedCounterparty.mThisBankId, thisBankId),
     By(MappedCounterparty.mThisAccountId, thisAccountId),
     By(MappedCounterparty.mThisViewId, thisViewId)
   )
  }


  private def getCounterpartyMetadata(counterpartyId : String) : Box[MappedCounterpartyMetadata] = {
    MappedCounterpartyMetadata.find(By(MappedCounterpartyMetadata.counterpartyId, counterpartyId))
  }

  override def getPublicAlias(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.publicAlias.get)
  }

  override def getPrivateAlias(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.privateAlias.get)
  }

  override def getPhysicalLocation(counterpartyId : String): Box[GeoTag] = {
    getCounterpartyMetadata(counterpartyId).flatMap(_.physicalLocation.obj)
  }

  override def getOpenCorporatesURL(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.getOpenCorporatesURL)
  }

  override def getImageURL(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.getImageURL)
  }

  override def getUrl(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.getUrl)
  }

  override def getMoreInfo(counterpartyId : String): Box[String] = {
    getCounterpartyMetadata(counterpartyId).map(_.getMoreInfo)
  }

  override def getCorporateLocation(counterpartyId : String): Box[GeoTag] = {
    getCounterpartyMetadata(counterpartyId).flatMap(_.corporateLocation.obj)
  }

  override def addPublicAlias(counterpartyId : String, alias: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.publicAlias(alias).save)
  }

  override def addPrivateAlias(counterpartyId : String, alias: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.privateAlias(alias).save)
  }

  override def addURL(counterpartyId : String, url: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.url(url).save)
  }

  override def addImageURL(counterpartyId : String, url: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.imageUrl(url).save)
  }

  override def addOpenCorporatesURL(counterpartyId : String, url: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.openCorporatesUrl(url).save)
  }

  override def addMoreInfo(counterpartyId : String, moreInfo: String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.moreInfo(moreInfo).save)
  }

  override def addPhysicalLocation(counterpartyId : String, userId: UserPrimaryKey, datePosted : Date, longitude : Double, latitude : Double): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.setPhysicalLocation(userId, datePosted, longitude, latitude))
  }

  override def addCorporateLocation(counterpartyId : String, userId: UserPrimaryKey, datePosted : Date, longitude : Double, latitude : Double): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).map(_.setCorporateLocation(userId, datePosted, longitude, latitude))
  }

  override def deletePhysicalLocation(counterpartyId : String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).flatMap(_.physicalLocation.obj).map(_.delete_!)
  }

  override def deleteCorporateLocation(counterpartyId : String): Box[Boolean] = {
    getCounterpartyMetadata(counterpartyId).flatMap(_.corporateLocation.obj).map(_.delete_!)
  }
  
  override def bulkDeleteAllCounterparties(): Box[Boolean] = {
    Full(MappedCounterparty.bulkDelete_!!())
  }
}


// for now, there are two Counterparties: one is used for CreateCounterParty.Counterparty, the other is for getTransactions.Counterparty.
// 1st is created by app explicitly, when use `CreateCounterParty` endpoint. This will be stored in database .
// 2nd is generated by obp implicitly, when use `getTransactions` endpoint. This will not be stored in database, but we create the CounterpartyMetadata for it. And the CounterpartyMetadata is in database. 
// They are relevant somehow, but they are different data for now.

class MappedCounterpartyMetadata extends CounterpartyMetadata with LongKeyedMapper[MappedCounterpartyMetadata] with IdPK with CreatedUpdated {
  override def getSingleton = MappedCounterpartyMetadata

  //these define the counterparty, not metadata
  object counterpartyId extends UUIDString(this)
  object counterpartyName extends MappedString(this, 255) 

  //these define the obp account to which this counterparty belongs
  object thisBankId extends UUIDString(this)
  object thisAccountId extends AccountIdString(this)


  //this is the counterparty's metadata
  object publicAlias extends MappedString(this, 64)
  object privateAlias extends MappedString(this, 64)
  object moreInfo extends MappedString(this, 255)
  object url extends MappedString(this, 2000)
  object imageUrl extends MappedString(this, 2000)
  object openCorporatesUrl extends MappedString(this, 2000)

  object physicalLocation extends MappedLongForeignKey(this, MappedCounterpartyWhereTag)
  object corporateLocation extends MappedLongForeignKey(this, MappedCounterpartyWhereTag)

  /**
   * Evaluates f, and then attempts to save. If no exceptions are thrown and save executes successfully,
   * true is returned. If an exception is thrown or if the save fails, false is returned.
   * @param f the expression to evaluate (e.g. imageUrl("http://example.com/foo.png")
   * @return If saving the model worked after having evaluated f
   */
  private def trySave(f : => Any) : Boolean =
    tryo{
      f
      save
    }.getOrElse(false)

  private def setWhere(whereTag : Box[MappedCounterpartyWhereTag])
                      (userId: UserPrimaryKey, datePosted : Date, longitude : Double, latitude : Double) : Box[MappedCounterpartyWhereTag] = {
    val toUpdate = whereTag match {
      case Full(c) => c
      case _ => MappedCounterpartyWhereTag.create
    }

    tryo{
      toUpdate
        .user(userId.value)
        .date(datePosted)
        .geoLongitude(longitude)
        .geoLatitude(latitude)
        .saveMe
    }
  }

  def setCorporateLocation(userId: UserPrimaryKey, datePosted : Date, longitude : Double, latitude : Double) : Boolean = {
    //save where tag
    val savedWhere = setWhere(corporateLocation.obj)(userId, datePosted, longitude, latitude)
    //set where tag for counterparty
    savedWhere.map(location => trySave{corporateLocation(location)}).getOrElse(false)
  }

  def setPhysicalLocation(userId: UserPrimaryKey, datePosted : Date, longitude : Double, latitude : Double) : Boolean = {
    //save where tag
    val savedWhere = setWhere(physicalLocation.obj)(userId, datePosted, longitude, latitude)
    //set where tag for counterparty
    savedWhere.map(location => trySave{physicalLocation(location)}).getOrElse(false)
  }

  override def getCounterpartyId: String = counterpartyId.get
  override def getCounterpartyName: String = counterpartyName.get
  override def getPublicAlias: String = publicAlias.get
  override def getCorporateLocation: Option[GeoTag] =
    corporateLocation.obj
  override def getOpenCorporatesURL: String = openCorporatesUrl.get
  override def getMoreInfo: String = moreInfo.get
  override def getPrivateAlias: String = privateAlias.get
  override def getImageURL: String = imageUrl.get
  override def getPhysicalLocation: Option[GeoTag] =
    physicalLocation.obj
  override def getUrl: String = url.get

  override val addPhysicalLocation: (UserPrimaryKey, Date, Double, Double) => Boolean = setPhysicalLocation _
  override val addCorporateLocation: (UserPrimaryKey, Date, Double, Double) => Boolean = setCorporateLocation _
  override val addPrivateAlias: (String) => Boolean = (x) =>
    trySave{privateAlias(x)}
  override val addURL: (String) => Boolean = (x) =>
    trySave{url(x)}
  override val addMoreInfo: (String) => Boolean = (x) =>
    trySave{moreInfo(x)}
  override val addPublicAlias: (String) => Boolean = (x) =>
    trySave{publicAlias(x)}
  override val addOpenCorporatesURL: (String) => Boolean = (x) =>
    trySave{openCorporatesUrl(x)}
  override val addImageURL: (String) => Boolean = (x) =>
    trySave{imageUrl(x)}
  override val deleteCorporateLocation = () =>
    corporateLocation.obj.map(_.delete_!).getOrElse(false)
  override val deletePhysicalLocation = () =>
    physicalLocation.obj.map(_.delete_!).getOrElse(false)

}

object MappedCounterpartyMetadata extends MappedCounterpartyMetadata with LongKeyedMetaMapper[MappedCounterpartyMetadata] {
  override def dbIndexes =
    UniqueIndex(counterpartyId) ::
    super.dbIndexes
}

class MappedCounterpartyWhereTag extends GeoTag with LongKeyedMapper[MappedCounterpartyWhereTag] with IdPK with CreatedUpdated {

  def getSingleton = MappedCounterpartyWhereTag

  object user extends MappedLongForeignKey(this, ResourceUser)
  object date extends MappedDateTime(this)

  //TODO: require these to be valid latitude/longitudes
  object geoLatitude extends MappedDouble(this)
  object geoLongitude extends MappedDouble(this)

  override def postedBy: Box[User] = Users.users.vend.getUserByResourceUserId(user.get)
  override def datePosted: Date = date.get
  override def latitude: Double = geoLatitude.get
  override def longitude: Double = geoLongitude.get
}

object MappedCounterpartyWhereTag extends MappedCounterpartyWhereTag with LongKeyedMetaMapper[MappedCounterpartyWhereTag]




// for now, there are two Counterparties: one is used for CreateCounterParty.Counterparty, the other is for getTransactions.Counterparty.
// 1st is created by app explicitly, when use `CreateCounterParty` endpoint. This will be stored in database .
// 2nd is generated by obp implicitly, when use `getTransactions` endpoint. This will not be stored in database, but we create the CounterpartyMetadata for it. And the CounterpartyMetadata is in database. 
// They are relevant somehow, but they are different data for now.
class MappedCounterparty extends CounterpartyTrait with LongKeyedMapper[MappedCounterparty] with IdPK with CreatedUpdated with OneToMany[Long, MappedCounterparty] {
  def getSingleton = MappedCounterparty

  object mCreatedByUserId extends MappedString(this, 36)
  object mName extends MappedString(this, 36)
  object mThisBankId extends MappedString(this, 36)
  object mThisAccountId extends AccountIdString(this)
  object mThisViewId extends MappedString(this, 36)
  object mCounterPartyId extends UUIDString(this)

  object mOtherBankRoutingScheme extends MappedString(this, 255)
  object mOtherBankRoutingAddress extends MappedString(this, 255)
  object mOtherBranchRoutingScheme extends MappedString(this, 255)
  object mOtherBranchRoutingAddress extends MappedString(this, 255)
  object mOtherAccountRoutingScheme extends MappedString(this, 255)
  object mOtherAccountRoutingAddress extends MappedString(this, 255)
  
  object mOtherAccountSecondaryRoutingScheme extends MappedString(this, 255)
  object mOtherAccountSecondaryRoutingAddress extends MappedString(this, 255)
  
  object mIsBeneficiary extends MappedBoolean(this)
  object mDescription extends MappedString(this, 36)
  object mCurrency extends MappedString(this, 255)

  object mBespoke extends MappedOneToMany(MappedCounterpartyBespoke, MappedCounterpartyBespoke.mCounterparty, OrderBy(MappedCounterpartyBespoke.id, Ascending))

  override def createdByUserId = mCreatedByUserId.get
  override def name = mName.get
  override def thisBankId = mThisBankId.get
  override def thisAccountId = mThisAccountId.get
  override def thisViewId = mThisViewId.get
  override def counterpartyId = mCounterPartyId.get
  
  override def otherBankRoutingScheme: String = mOtherBankRoutingScheme.get
  override def otherBankRoutingAddress: String = mOtherBankRoutingAddress.get
  override def otherBranchRoutingScheme: String = mOtherBranchRoutingScheme.get
  override def otherBranchRoutingAddress: String = mOtherBranchRoutingAddress.get
  override def otherAccountRoutingScheme = mOtherAccountRoutingScheme.get
  override def otherAccountRoutingAddress: String  = mOtherAccountRoutingAddress.get
  
  override def otherAccountSecondaryRoutingScheme: String = mOtherAccountSecondaryRoutingScheme.get
  override def otherAccountSecondaryRoutingAddress: String = mOtherAccountSecondaryRoutingAddress.get
  
  override def isBeneficiary: Boolean = mIsBeneficiary.get
  override def description: String = mDescription.get
  override def currency: String = mCurrency.toString
  override def bespoke: List[CounterpartyBespoke] = 
    CounterpartyBespokes.counterpartyBespokers.vend
      .getCounterpartyBespokesByCounterpartyId(this.id.get)
      .map(
        mappedBespoke=>CounterpartyBespoke(mappedBespoke.mKey.get,mappedBespoke.mVaule.get)
      )
}

object MappedCounterparty extends MappedCounterparty with LongKeyedMetaMapper[MappedCounterparty] {
  override def dbIndexes = 
    UniqueIndex(mCounterPartyId) :: 
      UniqueIndex(mName, mThisBankId, mThisAccountId, mThisViewId) :: 
//      UniqueIndex(mOtherBankRoutingScheme,mOtherBankRoutingAddress,mOtherBranchRoutingScheme,mOtherBranchRoutingAddress,mOtherAccountRoutingScheme,mOtherAccountRoutingAddress) :: 
//      UniqueIndex(mOtherAccountSecondaryRoutingScheme, mOtherAccountSecondaryRoutingAddress) :: 
      super.dbIndexes
}