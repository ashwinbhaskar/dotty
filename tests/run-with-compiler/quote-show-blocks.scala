import scala.quoted._
import given scala.quoted.autolift._

object Test {
  implicit val toolbox: scala.quoted.Toolbox = scala.quoted.Toolbox.make(getClass.getClassLoader)
  def main(args: Array[String]): Unit = run {
    def a(n: Int, x: Expr[Unit]): Expr[Unit] =
      if (n == 0) x
      else a(n - 1, '{ println(${n}); $x })

    println(a(5, '{}).show)


    def b(n: Int, x: Expr[Unit]): Expr[Unit] =
      if (n == 0) x
      else b(n - 1, '{ $x; println(${n}) })

    println(b(5, '{}).show)
    '{}
  }

}
