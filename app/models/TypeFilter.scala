package models

import edu.washington.cs.knowitall.browser.extraction.FreeBaseType
import edu.washington.cs.knowitall.common.enrich.Traversables.traversableOnceTo

sealed abstract trait TypeFilter {
  def typ: FreeBaseType
  def apply(answer: GroupTitlePart): Boolean
  def displayName: String
}

case class PositiveTypeFilter(override val typ: FreeBaseType) extends TypeFilter {
  def displayName = typ.typ.replaceAll("_", " ")

  def apply(answer: GroupTitlePart): Boolean =
    answer.types.exists(typ =>
      typ == this.typ)
}

case class NegativeTypeFilter(override val typ: FreeBaseType) extends TypeFilter {
  def displayName = typ.typ.replaceAll("_", " ")

  def apply(answer: GroupTitlePart): Boolean =
    answer.types.forall(typ =>
      typ != this.typ)
}

object TypeFilters {
  final val MINIMUM_OCCURRENCE = 2
  final val MAXIMUM_FILTER_COUNT = 25

  def fromGroups(query: Query, groups: Iterable[Group], debug: Boolean): Seq[TypeFilter] = {
    if (query.full) Seq.empty
    else {
      // build all possible filters
      val it = for {
        group <- groups

        // only use filters for "free" parts
        part <- group.title.parts
        if (query.freeParts.contains(part.extractionPart))

        // avoid user-submitted type categories
        typ <- part.types
        if typ.domain != "base" && typ.domain != "user"
      } yield (PositiveTypeFilter(typ))

      // order the filters and take the top few
      val ordered = it.histogram.filter {
        case (filter, count) =>
          count > MINIMUM_OCCURRENCE
      }.toSeq.sortBy(-_._2).map(_._1)

      if (debug) ordered
      else {
        val grouped = ordered.take(MAXIMUM_FILTER_COUNT).map(filter =>
          (filter, groups.toSet filter
            (group => group.title.parts exists filter.apply)))

        // remove groups that are a proper subset of another
        val filtered = grouped filter { case (filter, groups) =>
          !grouped.exists { case (otherFilter, otherGroups) =>
            !(filter eq otherFilter) &&
              otherGroups.size > groups.size &&
              groups.forall(otherGroups.contains) &&
              groups.size > 9 * otherGroups.size / 10
          }
        }

        grouped.map(_._1).toSeq
      }
    }
  }
}
