import scala.quoted._

object Test {
  implicit val toolbox: scala.quoted.Toolbox = scala.quoted.Toolbox.make(getClass.getClassLoader)
  def eval1(ff: Expr[Int => Int]) given QuoteContext: Expr[Int] = '{$ff(42)}

  def peval1() given QuoteContext: Expr[Unit] = '{
    def f(x: Int): Int = ${eval1('f)}
  }

  def main(args: Array[String]): Unit = withQuoteContext {
    val p = peval1()
    println(p.show)
  }

}