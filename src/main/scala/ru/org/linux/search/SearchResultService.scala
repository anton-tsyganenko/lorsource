/*
 * Copyright 1998-2015 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.search

import com.sksamuel.elastic4s.RichSearchHit
import com.typesafe.scalalogging.StrictLogging
import org.elasticsearch.search.aggregations.Aggregations
import org.elasticsearch.search.aggregations.bucket.filter.Filter
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTerms
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import ru.org.linux.group.GroupDao
import ru.org.linux.section.{Section, SectionService}
import ru.org.linux.tag.{TagRef, TagService}
import ru.org.linux.user.{User, UserService}
import ru.org.linux.util.StringUtil

import scala.beans.BeanProperty
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

case class SearchItem (
  @BeanProperty title:String,
  @BeanProperty postdate:DateTime,
  @BeanProperty user:User, // TODO use UserRef
  @BeanProperty message:String,
  @BeanProperty url:String,
  @BeanProperty score:Float,
  @BeanProperty comment:Boolean,
  @BeanProperty tags:java.util.List[TagRef]
)

@Service
class SearchResultsService @Autowired() (
  userService:UserService, sectionService:SectionService, groupDao:GroupDao
) extends StrictLogging {
  import ru.org.linux.search.SearchResultsService._

  def prepareAll(docs:java.lang.Iterable[RichSearchHit]) = (docs map prepare).asJavaCollection

  def prepare(doc:RichSearchHit):SearchItem = {
    val author = userService.getUserCached(doc.field("author").value[String])

    val postdate = isoDateTime.parseDateTime(doc.field("postdate").value[String])

    val comment = doc.field("is_comment").value[Boolean]

    val tags = if (comment) {
      Seq()
    } else {
      if (doc.fields.containsKey("tag")) {
        doc.field("tag").values.map(
          tag => TagService.tagRef(tag.toString))
      } else {
        Seq()
      }
    }

    SearchItem(
      title = getTitle(doc),
      postdate = postdate,
      user = author,
      url = getUrl(doc),
      score = doc.score,
      comment = comment,
      message = getMessage(doc),
      tags = tags
    )
  }

  private def getTitle(doc: RichSearchHit):String = {
    val itemTitle = doc.highlightFields.get("title").map(_.fragments()(0).string)
      .orElse(doc.fields.get("title") map { v ⇒ StringUtil.escapeHtml(v.value[String]) } )

    itemTitle.filter(!_.trim.isEmpty).orElse(
      doc.highlightFields.get("topic_title").map(_.fragments()(0).string))
        .getOrElse(StringUtil.escapeHtml(doc.fields("topic_title").value[String]))
  }

  private def getMessage(doc: RichSearchHit): String = {
    doc.highlightFields.get("message").map(_.fragments()(0).string) getOrElse {
      StringUtil.escapeHtml(doc.fields("message").value[String].take(SearchViewer.MessageFragment))
    }
  }

  private def getUrl(doc: RichSearchHit): String = {
    val section = SearchResultsService.section(doc)
    val msgid = doc.id

    val comment = doc.field("is_comment").value[Boolean]
    val topic = doc.field("topic_id").value[Int]
    val group = SearchResultsService.group(doc)

    if (comment) {
      val builder = UriComponentsBuilder.fromPath("/{section}/{group}/{msgid}?cid={cid}")
      builder.buildAndExpand(section, group, new Integer(topic), msgid).toUriString
    } else {
      val builder = UriComponentsBuilder.fromPath("/{section}/{group}/{msgid}")
      builder.buildAndExpand(section, group, new Integer(topic)).toUriString
    }
  }

  def buildSectionFacet(sectionFacet: Filter, selected:Option[String]): java.util.List[FacetItem] = {
    def mkItem(urlName:String, count:Long) = {
      val name = sectionService.nameToSection.get(urlName).map(_.getName).getOrElse(urlName).toLowerCase
      FacetItem(urlName, s"$name ($count)")
    }

    val agg = sectionFacet.getAggregations.get[Terms]("sections")

    val items = for (entry <- agg.getBuckets.toSeq) yield {
      mkItem(entry.getKeyAsString, entry.getDocCount)
    }

    val missing = selected.filter(key ⇒ items.forall(_.key !=key)).map(mkItem(_, 0)).toSeq

    val all = FacetItem("", s"все (${sectionFacet.getDocCount})")

    all +: (missing ++ items)
  }

  def buildGroupFacet(maybeSection: Option[Terms.Bucket], selected:Option[(String, String)]): java.util.List[FacetItem] = {
    def mkItem(section:Section, groupUrlName:String, count:Long) = {
      val group = groupDao.getGroup(section, groupUrlName)
      val name = group.getTitle.toLowerCase
      FacetItem(groupUrlName, s"$name ($count)")
    }

    val facetItems = for {
      selectedSection <- maybeSection.toSeq
      groups = selectedSection.getAggregations.get[Terms]("groups")
      section = sectionService.getSectionByName(selectedSection.getKeyAsString)
      entry <- groups.getBuckets.toSeq
    } yield {
      mkItem(section, entry.getKeyAsString, entry.getDocCount)
    }

    val missing = selected.filter(key ⇒ facetItems.forall(_.key != key._2)).map(p ⇒
      mkItem(sectionService.getSectionByName(p._1), p._2, 0)
    ).toSeq

    val items = facetItems ++ missing

    if (items.size > 1 || selected.isDefined) {
      val all = FacetItem("", s"все (${maybeSection.map(_.getDocCount).getOrElse(0)})")

      all +: items
    } else {
      null
    }
  }

  def foundTags(agg:Aggregations):java.util.List[TagRef] = {
    val tags = agg.get[SignificantTerms]("tags")

    tags.getBuckets.map(bucket => TagService.tagRef(bucket.getKeyAsString)).toSeq
  }
}

object SearchResultsService {
  private val isoDateTime = ISODateTimeFormat.dateTime

  def postdate(doc:RichSearchHit) = isoDateTime.parseDateTime(doc.field("postdate").value[String])
  def section(doc:RichSearchHit) = doc.field("section").value[String]
  def group(doc:RichSearchHit) = doc.field("group").value[String]
}

case class FacetItem(@BeanProperty key:String, @BeanProperty label:String)


