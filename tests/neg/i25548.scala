//> using options -Wconf:any:error -Wconf:cat=deprecation:warning -Werror

class Location @deprecated("", "") (value: String)
object Location {
  def apply(value: String): Location = new Location(value) // error
}
